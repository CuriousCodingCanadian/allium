package dev.hugeblank.allium.util.docs.lua;

import me.basiqueevangelist.enhancedreflection.api.EClass;
import dev.hugeblank.allium.util.Mappings;

public class LuaClassDocument {
    private final EClass<?> clazz;

    public LuaClassDocument(EClass<?> clazz) {
        this.clazz = clazz;
    }

    @Override
    public String toString() {
        return "--- " + Mappings.asClass(clazz);
    }
}
