package dev.su5ed.sinytra.connector.transformer;

import dev.su5ed.sinytra.adapter.patch.selector.AnnotationHandle;
import dev.su5ed.sinytra.adapter.patch.util.MethodQualifier;
import net.minecraftforge.fart.api.ClassProvider;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.fart.internal.ClassProviderImpl;
import net.minecraftforge.fart.internal.EnhancedClassRemapper;
import net.minecraftforge.fart.internal.EnhancedRemapper;
import net.minecraftforge.fart.internal.RenamingTransformer;
import net.minecraftforge.srgutils.IMappingFile;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class OptimizedRenamingTransformer extends RenamingTransformer {
    private static final String CLASS_DESC_PATTERN = "^L[a-zA-Z0-9/$_]+;$";
    private static final String FQN_CLASS_NAME_PATTERN = "^([a-zA-Z0-9$_]+\\.)*[a-zA-Z0-9$_]+$";

    public static Transformer create(ClassProvider classProvider, Consumer<String> log, IMappingFile mappingFile, Map<String, String> flatMappings) {
        RenamingClassProvider reverseProvider = new RenamingClassProvider(classProvider, mappingFile, mappingFile.reverse(), log);
        EnhancedRemapper enhancedRemapper = new RelocatingEnhancedRemapper(reverseProvider, mappingFile, flatMappings, log);
        return new OptimizedRenamingTransformer(enhancedRemapper, false);
    }

    public OptimizedRenamingTransformer(EnhancedRemapper remapper, boolean collectAbstractParams) {
        super(remapper, collectAbstractParams);
    }

    @Override
    protected void postProcess(ClassNode node) {
        super.postProcess(node);

        // Remap raw values (usually found in reflection calls) and unmapped mixin annotations
        // This is done in a "post-processing" phase rather than inside the main remapper's mapValue method
        // so that we're able to determine the "remap" mixin annotation value ahead of time, and only remap it when necessary
        PostProcessRemapper postProcessRemapper = new PostProcessRemapper(((RelocatingEnhancedRemapper) this.remapper).flatMappings, this.remapper);
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations != null) {
                // If remap has been set to false during compilation, we must manually map the annotation values ourselves instead of relying on the provided refmap
                if (method.visibleAnnotations.stream().anyMatch(ann -> new AnnotationHandle(ann).<Boolean>getValue("remap").map(h -> !h.get()).orElse(false))) {
                    for (AnnotationNode annotation : method.visibleAnnotations) {
                        postProcessRemapper.mapAnnotationValues(annotation.values);
                    }
                }
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc) {
                    ldc.cst = postProcessRemapper.mapValue(ldc.cst);
                }
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        indy.bsmArgs[i] = postProcessRemapper.mapValue(indy.bsmArgs[i]);
                        indy.bsm = (Handle) postProcessRemapper.mapValue(indy.bsm);
                    }
                }
            }
        }
        for (FieldNode field : node.fields) {
            field.value = postProcessRemapper.mapValue(field.value);
        }
    }

    private static class PostProcessRemapper {
        private final Map<String, String> flatMappings;
        private final Remapper remapper;

        public PostProcessRemapper(Map<String, String> flatMappings, Remapper remapper) {
            this.flatMappings = flatMappings;
            this.remapper = remapper;
        }

        public void mapAnnotationValues(List<Object> values) {
            if (values != null) {
                for (int i = 1; i < values.size(); i += 2) {
                    values.set(i, mapAnnotationValue(values.get(i)));
                }
            }
        }

        public Object mapAnnotationValue(Object obj) {
            if (obj instanceof AnnotationNode annotation) {
                mapAnnotationValues(annotation.values);
            }
            else if (obj instanceof List list) {
                list.replaceAll(this::mapAnnotationValue);
            }
            else {
                return mapValue(obj);
            }
            return obj;
        }

        public Object mapValue(Object value) {
            if (value instanceof String str) {
                if (str.matches(CLASS_DESC_PATTERN)) {
                    String mapped = flatMappings.get(str.substring(1, str.length() - 1));
                    if (mapped != null) {
                        return 'L' + mapped + ';';
                    }
                }
                else if (str.matches(FQN_CLASS_NAME_PATTERN)) {
                    String mapped = flatMappings.get(str.replace('.', '/'));
                    if (mapped != null) {
                        return mapped.replace('/', '.');
                    }
                }

                MethodQualifier qualifier = MethodQualifier.create(str).orElse(null);
                if (qualifier != null && qualifier.desc() != null) {
                    String owner = qualifier.owner() != null ? this.remapper.mapDesc(qualifier.owner()) : "";
                    String name = qualifier.name() != null ? this.flatMappings.getOrDefault(qualifier.name(), qualifier.name()) : "";
                    String desc = qualifier.desc() != null ? this.remapper.mapMethodDesc(qualifier.desc()) : "";
                    return owner + name + desc;
                }

                String mapped = this.flatMappings.get(str);
                if (mapped != null) {
                    return mapped;
                }
            }
            return this.remapper.mapValue(value);
        }
    }

    private static final class RenamingClassProvider implements ClassProvider {
        private final ClassProvider upstream;
        private final IMappingFile forwardMapping;
        private final EnhancedRemapper remapper;

        private final Map<String, Optional<IClassInfo>> classCache = new ConcurrentHashMap<>();

        private RenamingClassProvider(ClassProvider upstream, IMappingFile forwardMapping, IMappingFile reverseMapping, Consumer<String> log) {
            this.upstream = upstream;
            this.forwardMapping = forwardMapping;
            this.remapper = new EnhancedRemapper(upstream, reverseMapping, log);
        }

        @Override
        public Optional<? extends IClassInfo> getClass(String s) {
            return this.classCache.computeIfAbsent(s, this::computeClassInfo)
                .or(() -> this.upstream.getClass(s));
        }

        @Override
        public Optional<byte[]> getClassBytes(String cls) {
            return this.upstream.getClassBytes(this.forwardMapping.remapClass(cls));
        }

        private Optional<IClassInfo> computeClassInfo(String cls) {
            return getClassBytes(cls).map(data -> {
                ClassReader reader = new ClassReader(data);
                ClassWriter writer = new ClassWriter(0);
                ClassRemapper remapper = new EnhancedClassRemapper(writer, this.remapper, null);
                MixinTargetAnalyzer analyzer = new MixinTargetAnalyzer(Opcodes.ASM9, remapper);
                reader.accept(analyzer, ClassReader.SKIP_CODE);
                analyzer.targets.remove(cls);

                byte[] remapped = writer.toByteArray();
                IClassInfo info = new ClassProviderImpl.ClassInfo(remapped);
                return !analyzer.targets.isEmpty() ? new MixinClassInfo(info, analyzer.targets) : info;
            });
        }

        @Override
        public void close() throws IOException {
            this.upstream.close();
        }
    }

    private static class RelocatingEnhancedRemapper extends EnhancedRemapper {
        private final Map<String, String> flatMappings;

        public RelocatingEnhancedRemapper(ClassProvider classProvider, IMappingFile map, Map<String, String> flatMappings, Consumer<String> log) {
            super(classProvider, map, log);
            this.flatMappings = flatMappings;
        }

        @Override
        public String map(final String key) {
            String fastMapped = this.flatMappings.get(key);
            if (fastMapped != null) {
                return fastMapped;
            }
            return super.map(key);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String fastMapped = this.flatMappings.get(name);
            if (fastMapped != null) {
                return fastMapped;
            }
            return this.classProvider.getClass(owner)
                .map(cls -> {
                    if (cls instanceof MixinClassInfo mcls) {
                        for (String parent : mcls.computedParents()) {
                            String mapped = super.mapFieldName(parent, name, descriptor);
                            if (!name.equals(mapped)) {
                                return mapped;
                            }
                        }
                    }
                    return null;
                })
                .orElseGet(() -> super.mapFieldName(owner, name, descriptor));
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return this.classProvider.getClass(owner)
                .map(cls -> {
                    // Handle methods belonging to interfaces added through @Implements
                    if (cls instanceof MixinClassInfo && !name.startsWith("lambda$")) {
                        int interfacePrefix = name.indexOf("$");
                        if (interfacePrefix > -1 && name.lastIndexOf("$") == interfacePrefix) {
                            String actualName = name.substring(interfacePrefix + 1);
                            String fastMapped = this.flatMappings.get(actualName);
                            String mapped = fastMapped != null ? fastMapped : mapMethodName(owner, actualName, descriptor);
                            return name.substring(0, interfacePrefix + 1) + mapped;
                        }
                    }
                    return this.flatMappings.get(name);
                })
                .orElseGet(() -> super.mapMethodName(owner, name, descriptor));
        }

        @Override
        public String mapPackageName(String name) {
            // We don't need to map these
            return name;
        }
    }

    private static class MixinTargetAnalyzer extends ClassVisitor {
        private final Set<String> targets = new HashSet<>();

        public MixinTargetAnalyzer(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new MixinAnnotationVisitor(this.api, super.visitAnnotation(descriptor, visible), this.targets, null);
        }
    }

    private static class MixinAnnotationVisitor extends AnnotationVisitor {
        private final Set<String> targets;
        private final String attributeName;

        public MixinAnnotationVisitor(int api, AnnotationVisitor annotationVisitor, Set<String> targets, String attributeName) {
            super(api, annotationVisitor);

            this.targets = targets;
            this.attributeName = attributeName;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name, value);
            if ("value".equals(this.attributeName) && value instanceof Type type) {
                this.targets.add(type.getInternalName());
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new MixinAnnotationVisitor(this.api, super.visitArray(name), this.targets, name);
        }
    }

    private record MixinClassInfo(ClassProvider.IClassInfo wrapped, Set<String> computedParents) implements ClassProvider.IClassInfo {
        // Hacky way to "inject" members from the computed parent while preserving the real ones
        @Override
        public Collection<String> getInterfaces() {
            return Stream.concat(this.wrapped.getInterfaces().stream(), this.computedParents.stream()).toList();
        }

        //@formatter:off
        @Override public int getAccess() {return this.wrapped.getAccess();}
        @Override public String getName() {return this.wrapped.getName();}
        @Override public @Nullable String getSuper() {return this.wrapped.getSuper();}
        @Override public Collection<? extends ClassProvider.IFieldInfo> getFields() {return this.wrapped.getFields();}
        @Override public Optional<? extends ClassProvider.IFieldInfo> getField(String name) {return this.wrapped.getField(name);}
        @Override public Collection<? extends ClassProvider.IMethodInfo> getMethods() {return this.wrapped.getMethods();}
        @Override public Optional<? extends ClassProvider.IMethodInfo> getMethod(String name, String desc) {return this.wrapped.getMethod(name, desc);}
        //@formatter:on
    }
}
