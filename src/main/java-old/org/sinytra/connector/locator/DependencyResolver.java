package org.sinytra.connector.locator;

import com.google.common.base.Suppliers;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Multimap;
import com.mojang.logging.LogUtils;
import org.sinytra.connector.loader.ConnectorEarlyLoader;
import org.sinytra.connector.transformer.jar.JarTransformer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.FMLModMetadata;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.BuiltinMetadataWrapper;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.version.VersionParser;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.locating.IModFile;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

public final class DependencyResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final VersionOverrides VERSION_OVERRIDES = new VersionOverrides();
    public static final Supplier<DependencyOverrides> DEPENDENCY_OVERRIDES = Suppliers.memoize(() -> loadConfigFile("fabric_loader_dependencies.json", () -> new DependencyOverrides(FMLPaths.CONFIGDIR.get())));

    public static List<JarTransformer.TransformableJar> resolveDependencies(Collection<JarTransformer.TransformableJar> keys, Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> jars, Iterable<IModFile> loadedMods) {
        // Add global mod aliases
        FabricLoaderImpl.INSTANCE.aliasMods(ConnectorConfig.INSTANCE.get().globalModAliases());
        BiMap<JarTransformer.TransformableJar, ModCandidate> jarToCandidate = HashBiMap.create();
        // Fabric candidates
        List<ModCandidate> candidates = createCandidatesRecursive(keys, keys, jars, jarToCandidate);
        // Forge dependencies
        Stream<ModCandidate> forgeCandidates = StreamSupport.stream(loadedMods.spliterator(), false)
            .flatMap(modFile -> modFile.getModFileInfo() != null ? modFile.getModInfos().stream() : Stream.empty())
            .map(modInfo -> ModCandidate.createPlain(List.of(modInfo.getOwningFile().getFile().getFilePath()), new BuiltinMetadataWrapper(new FMLModMetadata(modInfo)), false, List.of()));
        Stream<ModCandidate> builtinCandidates = Stream.of(createJavaMod(), createFabricLoaderMod());
        // Merge
        List<ModCandidate> allCandidates = Stream.of(candidates.stream(), forgeCandidates, builtinCandidates).flatMap(Function.identity()).toList();

        EnvType envType = FabricLoader.getInstance().getEnvironmentType();
        try {
            List<ModCandidate> resolved = ModResolver.resolve(allCandidates, envType, Map.of());
            List<JarTransformer.TransformableJar> candidateJars = resolved.stream()
                .map(jarToCandidate.inverse()::get)
                .filter(Objects::nonNull)
                .filter(jar -> jar.modPath().metadata().modMetadata().loadsInEnvironment(envType))
                .toList();
            LOGGER.info("Dependency resolution found {} candidates to load", candidateJars.size());
            return candidateJars;
        } catch (ModResolutionException e) {
            throw ConnectorEarlyLoader.createLoadingException(e, e.getMessage().replaceAll("\t", "  "), false);
        }
    }

    public static void removeAliasedModDependencyConstraints(LoaderModMetadata metadata) {
        Multimap<String, String> aliases = ConnectorConfig.INSTANCE.get().globalModAliases();
        Collection<ModDependency> mapped = metadata.getDependencies().stream()
            .map(dep -> {
                // Aliased mods typically don't follow the same version convention as the original,
                // therefore we must widen all dependency constraints to wildcards 
                if (aliases.keys().contains(dep.getModId()) || aliases.values().contains(dep.getModId())) {
                    return dep.getKind() == ModDependency.Kind.BREAKS ? null : uncheck(() -> new ModDependencyImpl(dep.getKind(), dep.getModId(), List.of("*")));
                }
                return dep;
            })
            .filter(Objects::nonNull)
            .toList();
        metadata.setDependencies(mapped);
    }

    private static List<ModCandidate> createCandidatesRecursive(Collection<JarTransformer.TransformableJar> candidateJars, Collection<JarTransformer.TransformableJar> jarsToLoad, Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentsToChildren, Map<JarTransformer.TransformableJar, ModCandidate> jarToCandidate) {
        List<ModCandidate> candidates = new ArrayList<>();
        for (JarTransformer.TransformableJar candidateJar : candidateJars) {
            if (jarsToLoad.contains(candidateJar)) {
                ModCandidate candidate = jarToCandidate.computeIfAbsent(candidateJar, j -> {
                    Collection<JarTransformer.TransformableJar> children = parentsToChildren.containsKey(candidateJar) ? parentsToChildren.get(candidateJar) : List.of();
                    List<ModCandidate> childCandidates = createCandidatesRecursive(children, jarsToLoad, parentsToChildren, jarToCandidate);
                    List<Path> paths = parentsToChildren.containsValue(candidateJar) ? null : List.of(candidateJar.modPath().path());
                    ModCandidate parent = ModCandidate.createPlain(paths, candidateJar.modPath().metadata().modMetadata(), false, childCandidates);
                    for (ModCandidate childCandidate : childCandidates) {
                        childCandidate.addParent(parent);
                    }
                    return parent;
                });
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static ModCandidate createJavaMod() {
        ModMetadata metadata = new BuiltinModMetadata.Builder("java", System.getProperty("java.specification.version").replaceFirst("^1\\.", ""))
            .setName(System.getProperty("java.vm.name"))
            .build();
        GameProvider.BuiltinMod builtinMod = new GameProvider.BuiltinMod(Collections.singletonList(Paths.get(System.getProperty("java.home"))), metadata);

        return ModCandidate.createBuiltin(builtinMod, VERSION_OVERRIDES, DEPENDENCY_OVERRIDES.get());
    }

    private static ModCandidate createFabricLoaderMod() {
        String version = EmbeddedDependencies.getFabricLoaderVersion();
        if (version == null) {
            version = "0.0NONE";
        } else {
            // The patch version can be a wildcard, as some mods like to depend on the newest FLoader version
            // even if it's just a bugfix that they didn't need
            final String[] components = version.split("\\.");
            version = components[0] + "." + components[1] + ".*";
        }
        ModMetadata metadata;

        try {
            metadata = new BuiltinModMetadata.Builder("fabricloader", VersionParser.parse(version, true))
                .setName("Fabric Loader")
                .build();
        } catch (VersionParsingException e) {
            throw new RuntimeException(e);
        }

        GameProvider.BuiltinMod builtinMod = new GameProvider.BuiltinMod(Collections.singletonList(Path.of(uncheck(() -> FabricLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()))), metadata);

        return ModCandidate.createBuiltin(builtinMod, VERSION_OVERRIDES, DEPENDENCY_OVERRIDES.get());
    }

    private static <T> T loadConfigFile(String name, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            throw ConnectorEarlyLoader.createGenericLoadingException(t, "Invalid config file " + name);
        }
    }
}
