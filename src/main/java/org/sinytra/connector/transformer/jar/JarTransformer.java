package org.sinytra.connector.transformer.jar;

import com.google.common.base.Stopwatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.minecraftforge.fart.api.ClassProvider;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.locating.IModFile;
import org.jetbrains.annotations.Nullable;
import org.sinytra.adapter.patch.api.PatchAuditTrail;
import org.sinytra.connector.ConnectorEarlyLoader;
import org.sinytra.connector.locator.ConnectorFabricModMetadata;
import org.sinytra.connector.locator.DependencyResolver;
import org.sinytra.connector.util.ConnectorUtil;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.spongepowered.asm.launch.MixinLaunchPluginLegacy;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.uncheck;

public final class JarTransformer {
    public static final String SOURCE_NAMESPACE = "intermediary";
    public static final String OBF_NAMESPACE = "mojang";
    public static final Marker TRANSFORM_MARKER = MarkerFactory.getMarker("TRANSFORM");
    private static final String MAPPED_SUFFIX = "_mapped_moj_" + FMLLoader.versionInfo().mcVersion();
    // Keep this outside of BytecodeFixerUpperFrontend to prevent unnecessary static init of patches when we only need the jar path
    private static final Path GENERATED_JAR_PATH = ConnectorUtil.CONNECTOR_FOLDER.resolve("adapter/adapter_generated_mixins.jar");
    private static final String LOOM_GENERATED_PROPERTY = "fabric-loom:generated";
    private static final String LOOM_REMAP_ATTRIBUTE = "Fabric-Loom-Remap";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final VarHandle TRANSFORMER_LOADER_FIELD = uncheck(() -> MethodHandles.privateLookupIn(MixinLaunchPluginLegacy.class, MethodHandles.lookup()).findVarHandle(MixinLaunchPluginLegacy.class, "transformerLoader", ILaunchPluginService.ITransformerLoader.class));

    private static void setMixinClassProvider(ILaunchPluginService.ITransformerLoader loader) {
        try {
            MixinLaunchPluginLegacy plugin = (MixinLaunchPluginLegacy) MixinService.getService().getBytecodeProvider();
            TRANSFORMER_LOADER_FIELD.set(plugin, loader);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Path getGeneratedJarPath() {
        return GENERATED_JAR_PATH;
    }

    public static List<TransformedFabricModPath> transform(List<TransformableJar> jars, List<Path> libs, Collection<IModFile> loadedMods) {
        List<TransformedFabricModPath> transformed = new ArrayList<>();

        List<Path> inputLibs = new ArrayList<>(libs);
        List<TransformableJar> needTransforming = new ArrayList<>();
        for (TransformableJar jar : jars) {
            if (jar.cacheFile().isUpToDate()) {
                transformed.add(jar.toTransformedPath());
            }
            else {
                needTransforming.add(jar);
            }
            inputLibs.add(jar.input().toPath());
        }

        if (!needTransforming.isEmpty()) {
            transformed.addAll(transformJars(needTransforming, inputLibs, loadedMods));
        }

        return transformed;
    }

    public static TransformableJar cacheTransformableJar(File input) throws IOException {
        Files.createDirectories(ConnectorUtil.CONNECTOR_FOLDER);
        String name = input.getName().split("\\.(?!.*\\.)")[0];
        Path output = ConnectorUtil.CONNECTOR_FOLDER.resolve(name + MAPPED_SUFFIX + ".jar");

        FabricModFileMetadata metadata = readModMetadata(input);
        FabricModPath path = new FabricModPath(output, metadata);
        ConnectorUtil.CacheFile cacheFile = ConnectorUtil.getCached(input.toPath(), output);
        return new TransformableJar(input, path, cacheFile);
    }

    private static List<TransformedFabricModPath> transformJars(List<TransformableJar> paths, List<Path> libs, Collection<IModFile> loadedMods) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Connector] Transforming Jars", paths.size());
        try {
            ProgressMeter initProgress = StartupNotificationManager.prependProgressBar("[Connector] Initializing Transformer", 0);
            JarTransformInstance transformInstance;
            try {
                ClassProvider classProvider = ClassProvider.fromPaths(libs.toArray(Path[]::new));
                EarlyJSCoremodTransformer transformingClassProvider = EarlyJSCoremodTransformer.create(classProvider, loadedMods);
                ILaunchPluginService.ITransformerLoader loader = name -> transformingClassProvider.getClassBytes(name.replace('.', '/')).orElseThrow(() -> new ClassNotFoundException(name));
                setMixinClassProvider(loader);
                transformInstance = new JarTransformInstance(transformingClassProvider, loadedMods, libs);
            } finally {
                initProgress.complete();
            }
            ExecutorService executorService = Executors.newFixedThreadPool(paths.size());
            List<Pair<File, Future<Pair<FabricModPath, PatchAuditTrail>>>> futures = paths.stream()
                .map(jar -> {
                    Future<Pair<FabricModPath, PatchAuditTrail>> future = executorService.submit(() -> {
                        Pair<FabricModPath, PatchAuditTrail> pair = jar.transform(transformInstance);
                        progress.increment();
                        return pair;
                    });
                    return Pair.of(jar.input(), future);
                })
                .toList();
            executorService.shutdown();
            if (!executorService.awaitTermination(1, TimeUnit.HOURS)) {
                throw new RuntimeException("Timed out waiting for jar remap");
            }
            List<TransformedFabricModPath> results = futures.stream()
                .map(pair -> {
                    try {
                        Pair<FabricModPath, PatchAuditTrail> result = pair.getSecond().get();
                        return new TransformedFabricModPath(pair.getFirst().toPath(), result.getFirst(), result.getSecond());
                    } catch (Throwable t) {
                        throw new ModLoadingException(ConnectorEarlyLoader.createGenericLoadingIssue(t, "Error transforming file " + pair.getFirst().getName()));
                    }
                })
                .filter(Objects::nonNull)
                .toList();
            uncheck(() -> transformInstance.getBfu().saveGeneratedAdapterJar());
            transformInstance.saveAuditReport();
            stopwatch.stop();
            LOGGER.debug(TRANSFORM_MARKER, "Processed all jars in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
            return results;
        } catch (InterruptedException ignored) {
            return List.of();
        } finally {
            cleanupEnvironment();
            progress.complete();
        }
    }

    private static FabricModFileMetadata readModMetadata(File input) throws IOException {
        try (JarFile jarFile = new JarFile(input)) {
            ConnectorFabricModMetadata metadata;
            Set<String> configs;
            try (InputStream ins = jarFile.getInputStream(jarFile.getEntry(ConnectorUtil.FABRIC_MOD_JSON))) {
                LoaderModMetadata rawMetadata = ModMetadataParser.parseMetadata(ins, "", Collections.emptyList(), DependencyResolver.VERSION_OVERRIDES, DependencyResolver.DEPENDENCY_OVERRIDES.get(), false);
                DependencyResolver.removeAliasedModDependencyConstraints(rawMetadata);
                metadata = new ConnectorFabricModMetadata(rawMetadata);

                configs = new HashSet<>(metadata.getMixinConfigs(FabricLoader.getInstance().getEnvironmentType()));
            } catch (ParseMetadataException e) {
                throw new RuntimeException(e);
            }
            boolean containsAT = jarFile.getEntry(ConnectorUtil.AT_PATH) != null;

            Set<String> refmaps = new HashSet<>();
            Set<String> mixinPackages = new HashSet<>();
            for (String configName : configs) {
                ZipEntry entry = jarFile.getEntry(configName);
                if (entry != null) {
                    readMixinConfigPackages(input, jarFile, entry, refmaps, mixinPackages);
                }
            }
            // Find additional configs that may not be listed in mod metadata
            jarFile.stream()
                .forEach(entry -> {
                    String name = entry.getName();
                    if ((name.endsWith(".mixins.json") || name.startsWith("mixins.") && name.endsWith(".json")) && configs.add(name)) {
                        readMixinConfigPackages(input, jarFile, entry, refmaps, mixinPackages);
                    }
                });
            Attributes manifestAttributes = Optional.ofNullable(jarFile.getManifest()).map(Manifest::getMainAttributes).orElseGet(Attributes::new);
            boolean generated = isGeneratedLibraryJarMetadata(manifestAttributes, metadata);
            return new FabricModFileMetadata(metadata, Set.copyOf(configs), configs, refmaps, mixinPackages, manifestAttributes, containsAT, generated);
        }
    }

    private static boolean isGeneratedLibraryJarMetadata(Attributes manifestAttributes, LoaderModMetadata metadata) {
        CustomValue generatedValue = metadata.getCustomValue(LOOM_GENERATED_PROPERTY);
        if (generatedValue != null && generatedValue.getType() == CustomValue.CvType.BOOLEAN && generatedValue.getAsBoolean()) {
            String loomRemapAttribute = manifestAttributes.getValue(LOOM_REMAP_ATTRIBUTE);
            return loomRemapAttribute == null || !loomRemapAttribute.equals("true");
        }
        return false;
    }

    private static void readMixinConfigPackages(File input, JarFile jarFile, ZipEntry entry, Set<String> refmaps, Set<String> packages) {
        try (Reader reader = new InputStreamReader(jarFile.getInputStream(entry))) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("refmap")) {
                String refmap = json.get("refmap").getAsString();
                refmaps.add(refmap);
            }
            if (json.has("package")) {
                String pkg = json.get("package").getAsString();
                if (!pkg.isEmpty()) {
                    String pkgPath = pkg.replace('.', '/') + '/';
                    packages.add(pkgPath);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading mixin config entry {} in file {}", entry.getName(), input.getAbsolutePath());
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanupEnvironment() {
        setMixinClassProvider(null);
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ClassInfo.class, MethodHandles.lookup());

            // Remove cached class infos, which might not be up-to-date
            VarHandle cacheField = lookup.findStaticVarHandle(ClassInfo.class, "cache", Map.class);
            Map<String, ClassInfo> cache = (Map<String, ClassInfo>) cacheField.get();
            cache.clear();

            VarHandle objectField = lookup.findStaticVarHandle(ClassInfo.class, "OBJECT", ClassInfo.class);
            ClassInfo object = (ClassInfo) objectField.get();

            cache.put("java/lang/Object", object);
        } catch (Throwable t) {
            LOGGER.error("Error cleaning up after jar transformation", t);
        }
    }

    private JarTransformer() {}

    public record FabricModPath(Path path, FabricModFileMetadata metadata) {}

    public record TransformedFabricModPath(Path input, FabricModPath output, @Nullable PatchAuditTrail auditTrail) {}

    public record FabricModFileMetadata(ConnectorFabricModMetadata modMetadata, Collection<String> visibleMixinConfigs, Collection<String> mixinConfigs, Set<String> refmaps, Set<String> mixinPackages, Attributes manifestAttributes, boolean containsAT, boolean generated) {}

    public record TransformableJar(File input, FabricModPath modPath, ConnectorUtil.CacheFile cacheFile) {
        public Pair<FabricModPath, PatchAuditTrail> transform(JarTransformInstance transformInstance) throws IOException {
            Files.deleteIfExists(this.modPath.path);
            PatchAuditTrail audit = transformInstance.transformJar(this.input, this.modPath.path, this.modPath.metadata());
            this.cacheFile.save();
            return Pair.of(this.modPath, audit);
        }

        public TransformedFabricModPath toTransformedPath() {
            return new TransformedFabricModPath(this.input.toPath(), this.modPath, null);
        }
    }
}
