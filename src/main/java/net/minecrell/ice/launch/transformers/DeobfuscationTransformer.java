/*
 * Ice
 * Copyright (c) 2015, Minecrell <https://github.com/Minecrell>
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

package net.minecrell.ice.launch.transformers;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.RemappingMethodAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public class DeobfuscationTransformer extends Remapper implements IClassTransformer, IClassNameTransformer {

    private final ImmutableBiMap<String, String> classes;
    private final ImmutableTable<String, String, String> rawFields;
    private final ImmutableTable<String, String, String> rawMethods;

    private final Map<String, Map<String, String>> fields;
    private final Map<String, Map<String, String>> methods;

    private final Set<String> failedFields = new HashSet<>();
    private final Set<String> failedMethods = new HashSet<>();

    private final Map<String, Map<String, String>> fieldDescriptions = new HashMap<>();

    public DeobfuscationTransformer() throws Exception {
        Path path = (Path) Launch.blackboard.get("ice.deobf-srg");
        String name = path.getFileName().toString();
        boolean gzip = name.endsWith(".gz");

        ImmutableBiMap.Builder<String, String> classes = ImmutableBiMap.builder();
        ImmutableTable.Builder<String, String, String> fields = ImmutableTable.builder();
        ImmutableTable.Builder<String, String, String> methods = ImmutableTable.builder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                gzip ? new GZIPInputStream(Files.newInputStream(path)) : Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ((line = line.trim()).isEmpty())
                    continue;

                String[] parts = StringUtils.split(line, ' ');
                if (parts.length < 3) {
                    System.out.println("Invalid line: " + line);
                    continue;
                }

                MappingType type = MappingType.of(parts[0]);
                if (type == null) {
                    System.out.println("Invalid mapping: " + line);
                    continue;
                }

                String[] source, dest;
                switch (type) {
                    case CLASS:
                        classes.put(parts[1], parts[2]);
                        break;
                    case FIELD:
                        source = getSignature(parts[1]);
                        dest = getSignature(parts[2]);
                        String fieldType = getFieldType(source[0], source[1]);
                        fields.put(source[0], source[1] + ':' + fieldType, dest[1]);
                        if (fieldType != null) fields.put(source[0], source[1] + ":null", dest[1]);
                        break;
                    case METHOD:
                        source = getSignature(parts[1]);
                        dest = getSignature(parts[3]);
                        methods.put(source[0], source[1] + parts[2], dest[1]);
                        break;
                }
            }
        }

        this.classes = classes.build();
        this.rawFields = fields.build();
        this.rawMethods = methods.build();

        this.fields = Maps.newHashMapWithExpectedSize(rawFields.size());
        this.methods = Maps.newHashMapWithExpectedSize(rawMethods.size());
    }

    private static String[] getSignature(String in) {
        int pos = in.lastIndexOf('/');
        return new String[] { in.substring(0, pos), in.substring(pos + 1) };
    }

    private static byte[] getBytes(String name) {
        try {
            return Launch.classLoader.getClassBytes(name);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    private String getFieldType(String owner, String name) {
        Map<String, String> fieldDescriptions = this.fieldDescriptions.get(owner);
        if (fieldDescriptions != null) return fieldDescriptions.get(name);

        byte[] bytes = getBytes(owner);
        if (bytes == null) return null;

        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String result = null;
        fieldDescriptions = Maps.newHashMapWithExpectedSize(classNode.fields.size());
        for (FieldNode fieldNode : classNode.fields) {
            fieldDescriptions.put(fieldNode.name, fieldNode.desc);
            if (fieldNode.name.equals(name)) {
                result = fieldNode.desc;
            }
        }

        this.fieldDescriptions.put(owner, fieldDescriptions);
        return result;
    }

    @Override
    public String map(String typeName) {
        if (classes == null) return typeName;
        String name = classes.get(typeName);
        return name != null ? name : typeName;
    }

    public String unmap(String typeName) {
        if (classes == null) return typeName;
        String name = classes.inverse().get(typeName);
        return name != null ? name : typeName;
    }

    @Override
    public String mapFieldName(String owner, String fieldName, String desc) {
        if (classes == null) return fieldName;
        Map<String, String> fields = getFieldMap(owner);
        if (fields != null) {
            String name = fields.get(fieldName + ':' + desc);
            if (name != null) return name;
        }

        return fieldName;
    }

    private Map<String, String> getFieldMap(String owner) {
        Map<String, String> result = fields.get(owner);
        if (result != null) return result;

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
        if (classes == null) return methodName;
        Map<String, String> methods = getMethodMap(owner);
        if (methods != null) {
            String name = methods.get(methodName + desc);
            if (name != null) return name;
        }

        return methodName;
    }

    private Map<String, String> getMethodMap(String owner) {
        Map<String, String> result = methods.get(owner);
        if (result != null) return result;

        if (!failedMethods.contains(owner)) {
            loadSuperMaps(owner);
            if (!methods.containsKey(owner)) {
                failedMethods.add(owner);
            }
        }

        return methods.get(owner);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return null;

        //System.out.println("\t> Deobfuscating " + name + " -> " + transformedName);
        ClassWriter writer = new ClassWriter(0);
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new RemappingAdapter(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }


    @Override
    public String remapClassName(String typeName) {
        return map(typeName.replace('.', '/')).replace('/', '.');
    }

    @Override
    public String unmapClassName(String typeName) {
        return unmap(typeName.replace('.', '/')).replace('/', '.');
    }

    private void loadSuperMaps(String name) {
        byte[] bytes = getBytes(name);
        if (bytes != null) {
            ClassReader reader = new ClassReader(bytes);
            createSuperMaps(name, reader.getSuperName(), reader.getInterfaces());
        }
    }

    void createSuperMaps(String name, String superName, String[] interfaces) {
        if (Strings.isNullOrEmpty(superName)) return;

        String[] parents = new String[interfaces.length + 1];
        parents[0] = superName;
        System.arraycopy(interfaces, 0, parents, 1, interfaces.length);

        for (String parent : parents) {
            if (!fields.containsKey(parent))
                loadSuperMaps(parent);
        }

        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();

        Map<String, String> m;
        for (String parent : parents) {
            m = this.fields.get(parent);
            if (m != null) fields.putAll(m);
            m = this.methods.get(parent);
            if (m != null) methods.putAll(m);
        }

        fields.putAll(rawFields.row(name));
        methods.putAll(rawMethods.row(name));

        this.fields.put(name, ImmutableMap.copyOf(fields));
        this.methods.put(name, ImmutableMap.copyOf(methods));
    }

    String getStaticFieldType(String oldType, String oldName, String newType, String newName) {
        String type = getFieldType(oldType, oldName);
        if (oldType.equals(newType)) return type;

        Map<String, String> newClassMap = fieldDescriptions.get(newType);
        if (newClassMap == null) {
            newClassMap = new HashMap<>();
            fieldDescriptions.put(newType, newClassMap);
        }
        newClassMap.put(newName, type);
        return type;
    }



    private enum MappingType {
        PACKAGE("PK"), CLASS("CL"), FIELD("FD"), METHOD("MD");

        private final String identifier;

        private MappingType(String identifier) {
            this.identifier = identifier;
        }

        private static final ImmutableMap<String, MappingType> LOOKUP;

        static {
            ImmutableMap.Builder<String, MappingType> builder = ImmutableMap.builder();
            for (MappingType type : MappingType.values()) {
                builder.put(type.identifier + ':', type);
            }
            LOOKUP = builder.build();
        }


        public static MappingType of(String identifier) {
            return LOOKUP.get(identifier);
        }

    }

    private class RemappingAdapter extends RemappingClassAdapter {

        public RemappingAdapter(ClassVisitor cv) {
            super(cv, DeobfuscationTransformer.this);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (interfaces == null) {
                interfaces = ArrayUtils.EMPTY_STRING_ARRAY;
            }

            createSuperMaps(name, superName, interfaces);
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        protected MethodVisitor createRemappingMethodAdapter(int access, String newDesc, MethodVisitor mv) {
            return new RemappingMethodAdapter(access, newDesc, mv, remapper) {

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                    String type = remapper.mapType(owner);
                    String fieldName = remapper.mapFieldName(owner, name, desc);
                    String newDesc = remapper.mapDesc(desc);
                    if (opcode == Opcodes.GETSTATIC && type.startsWith("net/minecraft/") && newDesc.startsWith("Lnet/minecraft/")) {
                        String replDesc = getStaticFieldType(owner, name, type, fieldName);
                        if (replDesc != null) {
                            newDesc = remapper.mapDesc(replDesc);
                        }
                    }

                    if (mv != null) {
                        mv.visitFieldInsn(opcode, type, fieldName, newDesc);
                    }
                }
            };
        }
    }

}
