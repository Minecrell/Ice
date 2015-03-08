/*
 * Quartz
 * Copyright (c) 2015, Minecrell <https://github.com/Minecrell>
 *
 * Based on Sponge and SpongeAPI, licensed under the MIT License (MIT).
 * Copyright (c) SpongePowered.org <http://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.minecrell.quartz.launch.transformers;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecrell.quartz.launch.mappings.MappingsLoader;
import org.apache.commons.lang3.ArrayUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MappingsTransformer extends Remapper implements IClassTransformer, IClassNameTransformer {

    private final ImmutableBiMap<String, String> classes;
    private final ImmutableTable<String, String, String> rawFields;
    private final ImmutableTable<String, String, String> rawMethods;

    private final Map<String, Map<String, String>> fields;
    private final Map<String, Map<String, String>> methods;

    private final Set<String> failedFields = new HashSet<>();
    private final Set<String> failedMethods = new HashSet<>();

    public MappingsTransformer() {
        this((MappingsLoader) Launch.blackboard.get("quartz.mappings"));
    }

    // For custom transformers
    protected MappingsTransformer(MappingsLoader loader) {
        requireNonNull(loader, "loader");

        this.classes = loader.loadClasses();

        Remapper inverse = new Remapper() {

            @Override
            public String map(String typeName) {
                return unmap(typeName);
            }
        };

        this.rawFields = loader.loadFields(inverse);
        this.rawMethods = loader.loadMethods(inverse);

        this.fields = Maps.newHashMapWithExpectedSize(rawFields.size());
        this.methods = Maps.newHashMapWithExpectedSize(rawMethods.size());
    }

    @Override
    public String map(String typeName) {
        String name = classes.get(typeName);
        if (name != null) {
            return name;
        }

        int innerClassPos = typeName.lastIndexOf('$');
        if (innerClassPos >= 0) {
            name = classes.get(typeName.substring(0, innerClassPos));
            if (name != null) {
                return name + typeName.substring(innerClassPos);
            }
        }

        return typeName;
    }

    public String unmap(String typeName) {
        String name = classes.inverse().get(typeName);
        if (name != null) {
            return name;
        }

        int innerClassPos = typeName.lastIndexOf('$');
        if (innerClassPos >= 0) {
            name = classes.inverse().get(typeName.substring(0, innerClassPos));
            if (name != null) {
                return name + typeName.substring(innerClassPos);
            }
        }

        return typeName;
    }

    @Override
    public String remapClassName(String name) {
        return map(name.replace('.', '/')).replace('/', '.');
    }

    @Override
    public String unmapClassName(String name) {
        return unmap(name.replace('.', '/')).replace('/', '.');
    }

    @Override
    public String mapFieldName(String owner, String fieldName, String desc) {
        Map<String, String> fields = getFieldMap(owner);
        if (fields != null) {
            String name = fields.get(fieldName + ':' + desc);
            if (name != null) {
                return name;
            }
        }

        return fieldName;
    }

    private Map<String, String> getFieldMap(String owner) {
        Map<String, String> result = fields.get(owner);
        if (result != null) {
            return result;
        }

        if (!failedFields.contains(owner)) {
            loadSuperMaps(owner);
            if (!fields.containsKey(owner)) {
                failedFields.add(owner);
            }
        }

        return fields.get(owner);
    }

    @Override
    public String mapMethodName(String owner, String methodName, String desc) {
        Map<String, String> methods = getMethodMap(owner);
        if (methods != null) {
            String name = methods.get(methodName + desc);
            if (name != null) {
                return name;
            }
        }

        return methodName;
    }

    private Map<String, String> getMethodMap(String owner) {
        Map<String, String> result = methods.get(owner);
        if (result != null) {
            return result;
        }

        if (!failedMethods.contains(owner)) {
            loadSuperMaps(owner);
            if (!methods.containsKey(owner)) {
                failedMethods.add(owner);
            }
        }

        return methods.get(owner);
    }

    private static byte[] getBytes(String name) {
        try {
            return Launch.classLoader.getClassBytes(name);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    private void loadSuperMaps(String name) {
        byte[] bytes = getBytes(name);
        if (bytes != null) {
            ClassReader reader = new ClassReader(bytes);
            createSuperMaps(name, reader.getSuperName(), reader.getInterfaces());
        }
    }

    private void createSuperMaps(String name, String superName, String[] interfaces) {
        if (Strings.isNullOrEmpty(superName)) {
            return;
        }

        String[] parents = new String[interfaces.length + 1];
        parents[0] = superName;
        System.arraycopy(interfaces, 0, parents, 1, interfaces.length);

        for (String parent : parents) {
            if (!fields.containsKey(parent)) {
                loadSuperMaps(parent);
            }
        }

        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();

        Map<String, String> m;
        for (String parent : parents) {
            m = this.fields.get(parent);
            if (m != null) {
                fields.putAll(m);
            }
            m = this.methods.get(parent);
            if (m != null) {
                methods.putAll(m);
            }
        }

        fields.putAll(rawFields.row(name));
        methods.putAll(rawMethods.row(name));

        this.fields.put(name, ImmutableMap.copyOf(fields));
        this.methods.put(name, ImmutableMap.copyOf(methods));
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        if (!transformedName.startsWith(MappingsLoader.PACKAGE_CLASS) && transformedName.indexOf('.') >= 0) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(0);
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new RemappingAdapter(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private class RemappingAdapter extends RemappingClassAdapter {

        public RemappingAdapter(ClassVisitor cv) {
            super(cv, MappingsTransformer.this);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (interfaces == null) {
                interfaces = ArrayUtils.EMPTY_STRING_ARRAY;
            }

            createSuperMaps(name, superName, interfaces);
            super.visit(version, access, name, signature, superName, interfaces);
        }

    }

}
