package dev.hugeblank.allium.loader;

import dev.hugeblank.allium.Allium;
import dev.hugeblank.allium.loader.resources.AlliumResourcePack;
import dev.hugeblank.allium.lua.type.annotation.LuaWrapped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.squiddev.cobalt.*;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.function.LuaFunction;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@LuaWrapped
public class Script {
    private static final Map<String, Script> SCRIPTS = new HashMap<>();

    // The Man(ifest) who can't be moved
    private final Manifest manifest;
    private final Logger logger;
    private final ScriptEnvironment executor;
    // Whether this script was able to register itself
    private boolean initialized = false; // Whether this scripts Lua side (static and dynamic) was able to execute
    protected LuaValue module;
    private final Path path;
    // Resources are stored in a weak set so that if a resource is abandoned, it gets destroyed.
    private final Set<ScriptResource> resources = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean destroyingResources = false;

    public Script(Manifest manifest, Path path) {
        this.manifest = manifest;
        this.path = path;
        this.executor = new ScriptEnvironment(this);
        this.logger = LoggerFactory.getLogger('@' + manifest.id());
        try {
            if (SCRIPTS.containsKey(manifest.id()))
                throw new Exception("Script with ID is already loaded!");
            SCRIPTS.put(manifest.id(), this);
            AlliumResourcePack.register(this);
        } catch (Exception e) {
            getLogger().error("Could not load allium script " + getId(), e);
            unload();
        }
    }

    public void reload() {
        destroyAllResources();

        // Re-run dynamic entrypoint again
        try {
            InputStream dynamicEntrypoint = manifest.entrypoints().hasDynamic() ?
                    Files.newInputStream(path.resolve(manifest.entrypoints().getDynamic())) :
                    null;
            // Reload and set the module if all that's provided is a dynamic script
            this.module = manifest.entrypoints().hasDynamic() ?
                    executor.reload(dynamicEntrypoint).arg(1) :
                    this.module;
        } catch (Throwable e) {
            getLogger().error("Could not reload allium script " + getId(), e);
            unload();
        }
    }

    @LuaWrapped
    public ResourceRegistration registerResource(ScriptResource resource) {
        resources.add(resource);

        return new ResourceRegistration(resource);
    }

    public class ResourceRegistration implements AutoCloseable {
        private final ScriptResource resource;

        private ResourceRegistration(ScriptResource resource) {
            this.resource = resource;
        }

        @Override
        public void close() {
            if (destroyingResources) return;

            resources.remove(resource);
        }
    }

    private void destroyAllResources() {
        if (destroyingResources) throw new IllegalStateException("Tried to recursively destroy resources!");

        destroyingResources = true;

        try {
            for (ScriptResource resource : resources) {
                try {
                    resource.close();
                } catch (Exception e) {
                    getLogger().error("Failed to close script resource", e);
                }
            }
        } finally {
            destroyingResources = false;

            resources.clear();
        }
    }

    public void unload() {
        SCRIPTS.remove(manifest.name(), this);
        destroyAllResources();
        this.executor.getState().abandon();
        AlliumResourcePack.drop(this);
    }

    public void initializeMixin() {

    }

    public void initialize() {
        if (isInitialized()) return;
        try {
            Entrypoint entrypoints = getManifest().entrypoints();
            // Create InputStreams for each entrypoint, if it exists
            InputStream staticEntrypoint = entrypoints.hasStatic() ?
                    Files.newInputStream(path.resolve(entrypoints.getStatic())) :
                    null;
            InputStream dynamicEntrypoint = entrypoints.hasDynamic() ?
                    Files.newInputStream(path.resolve(entrypoints.getDynamic())) :
                    null;
            // Initialize and set module used by require

            ScriptEnvironment env = getEnvironment();
            LuaState state = env.getState();

            env.onInitialize();
            state.setupThread(new LuaTable());
            Varargs out = null;
            if (entrypoints.hasStatic())
                out = env.run(staticEntrypoint, getId() + ":static");

            if (entrypoints.hasDynamic()) {
                Varargs temp = env.run(dynamicEntrypoint, getId() + ":dynamic");
                if (out == null) out = temp;
            }

            if (out == null) throw new Exception("Expected either static or dynamic entrypoint, got none");

            this.module = out.arg(1);
            this.initialized = true; // If all these steps are successful, we can set initialized to true
        } catch (Throwable e) {
            getLogger().error("Could not initialize allium script " + getId(), e);
            unload();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    // return null if file isn't contained within Scripts path, or if it doesn't exist.
    public LuaValue loadLibrary(LuaState state, Path mod) throws UnwindThrowable, LuaError {
        // Ensure the modules parent path is the root path, and that the module exists before loading
        try {
            LuaFunction loadValue = getEnvironment().load(Files.newInputStream(mod), mod.getFileName().toString());
            return loadValue.call(state);
        } catch (FileNotFoundException e) {
            // This should never happen, but if it does, boy do I want to know.
            Allium.LOGGER.warn("File claimed to exist but threw a not found exception...", e);
            return null;
        } catch (CompileException | IOException e) {
            throw new LuaError(e);
        }
    }

    @LuaWrapped
    public LuaValue getModule() {
        return module;
    }

    @LuaWrapped
    public Path getPath() {
        return path;
    }

    public Manifest getManifest() {
        return manifest;
    }

    @LuaWrapped
    public String getId() {
        return manifest.id();
    }

    @LuaWrapped
    public String getVersion() {
        return manifest.version();
    }

    @LuaWrapped
    public String getName() {
        return manifest.name();
    }

    public Logger getLogger() {
        return logger;
    }

    public ScriptEnvironment getEnvironment() {
        return executor;
    }

    public static Script getFromID(String id) {
        return SCRIPTS.get(id);
    }

    public static Collection<Script> getAllScripts() {
        return SCRIPTS.values();
    }

    @Override
    public String toString() {
        return manifest.name();
    }

    //  if ( i % 2 == 0) break;
}
