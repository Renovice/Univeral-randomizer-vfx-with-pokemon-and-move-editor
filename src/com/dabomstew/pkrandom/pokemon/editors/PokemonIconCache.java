package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.PokemonImageGetter;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small shared cache used by the editor panels to display party-style icons for species.
 * Icons are generated lazily from the RomHandler's {@link PokemonImageGetter} implementation.
 */
public final class PokemonIconCache {
    private static final int ICON_SIZE = 48;
    private static final String ICON_RESOURCE_ROOT = "/com/dabomstew/pkrandom/pokemon/icons/";
    private static final Set<Integer> DEFAULT_FORM_SPECIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(778, 664, 665, 414, 493, 773)));
    private static PokemonIconCache current;

    private final RomHandler romHandler;
    private final boolean supported;
    private final boolean useBundledIcons;
    private final Map<String, ImageIcon> bundledIconCache = new ConcurrentHashMap<>();
    private final Map<Species, ImageIcon> cache = new ConcurrentHashMap<>();
    private final Map<String, ImageIcon> scaledCache = new ConcurrentHashMap<>();

    private PokemonIconCache(RomHandler romHandler) {
        this.romHandler = romHandler;
        boolean hasGetter = romHandler != null && romHandler.hasPokemonImageGetter();
        this.useBundledIcons = !hasGetter && romHandler != null && hasBundledIconSupport(romHandler);
        this.supported = hasGetter || useBundledIcons;
    }

    public static synchronized PokemonIconCache get(RomHandler romHandler) {
        if (romHandler == null) {
            return new PokemonIconCache(null);
        }
        if (current == null || current.romHandler != romHandler) {
            current = new PokemonIconCache(romHandler);
        }
        return current;
    }

    public boolean hasIcons() {
        return supported;
    }

    public ImageIcon getIcon(Species species) {
        if (!supported || species == null) {
            return null;
        }
        return cache.computeIfAbsent(species, this::createIcon);
    }

    /**
     * Crisp icon at an arbitrary size, scaled from the native sprite with
     * nearest-neighbour interpolation so pixel art stays sharp (no blur).
     * Use this for large displays like the card view header.
     */
    public ImageIcon getScaledIcon(Species species, int size) {
        if (!supported || species == null || size <= 0) {
            return null;
        }
        String cacheKey = (species.getNumber()) + ":" + (species.getFormeNumber()) + ":" + size;
        return scaledCache.computeIfAbsent(cacheKey, k -> {
            BufferedImage source = loadSourceImage(species);
            if (source == null) {
                return null;
            }
            BufferedImage scaled = scaleNearest(source, size);
            return scaled == null ? null : new ImageIcon(scaled);
        });
    }

    /** Native (unscaled) sprite for a species, or null. */
    private BufferedImage loadSourceImage(Species species) {
        if (romHandler != null && romHandler.hasPokemonImageGetter()) {
            try {
                return romHandler.createPokemonImageGetter(species)
                        .setTransparentBackground(true)
                        .get();
            } catch (Exception e) {
                return null;
            }
        }
        if (useBundledIcons) {
            for (String candidate : buildCandidateResourceNames(species)) {
                BufferedImage image = loadBundledSource(candidate);
                if (image != null) {
                    return image;
                }
            }
            return loadBundledSource("unknown");
        }
        return null;
    }

    private BufferedImage loadBundledSource(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String resourcePath = ICON_RESOURCE_ROOT + name + ".png";
        try (InputStream stream = PokemonIconCache.class.getResourceAsStream(resourcePath)) {
            return stream == null ? null : ImageIO.read(stream);
        } catch (IOException e) {
            return null;
        }
    }

    /** Nearest-neighbour scale to a square of {@code size}, preserving aspect ratio and centering. */
    private static BufferedImage scaleNearest(BufferedImage image, int size) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = out.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double scale = Math.min((double) size / width, (double) size / height);
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        int x = (size - scaledWidth) / 2;
        int y = (size - scaledHeight) / 2;
        g2d.drawImage(image, x, y, scaledWidth, scaledHeight, null);
        g2d.dispose();
        return out;
    }

    private ImageIcon createIcon(Species species) {
        if (romHandler != null && romHandler.hasPokemonImageGetter()) {
            return createIconFromRom(species);
        }
        if (useBundledIcons) {
            return createIconFromBundle(species);
        }
        return null;
    }

    private ImageIcon createIconFromRom(Species species) {
        try {
            PokemonImageGetter getter = romHandler.createPokemonImageGetter(species)
                    .setTransparentBackground(true);
            BufferedImage image = getter.get();
            if (image == null) {
                return null;
            }
            BufferedImage scaled = scaleToIcon(image);
            if (scaled == null) {
                return null;
            }
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

    private ImageIcon createIconFromBundle(Species species) {
        for (String candidate : buildCandidateResourceNames(species)) {
            ImageIcon icon = loadBundledIcon(candidate);
            if (icon != null) {
                return icon;
            }
        }
        return loadBundledIcon("unknown");
    }

    private ImageIcon loadBundledIcon(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return bundledIconCache.computeIfAbsent(name, key -> {
            String resourcePath = ICON_RESOURCE_ROOT + key + ".png";
            try (InputStream stream = PokemonIconCache.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    return null;
                }
                BufferedImage image = ImageIO.read(stream);
                if (image == null) {
                    return null;
                }
                BufferedImage scaled = scaleToIcon(image);
                if (scaled == null) {
                    return null;
                }
                return new ImageIcon(scaled);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private List<String> buildCandidateResourceNames(Species species) {
        int baseNumber = species.getBaseNumber();
        int form = (!species.isBaseForme()) ? species.getFormeNumber() : 0;
        if (DEFAULT_FORM_SPECIES.contains(baseNumber)) {
            form = 0;
        }

        List<String> candidates = new ArrayList<>();
        StringBuilder baseKey = new StringBuilder().append(baseNumber);
        if (form > 0) {
            baseKey.append('_').append(form);
            if (baseNumber == 25 && generationForIcons() >= 7) {
                baseKey.append('c');
            }
        }

        String key = baseKey.toString();
        if (!key.equals(String.valueOf(baseNumber))) {
            candidates.add(key.replace('_', '-'));
            candidates.add(key);
            candidates.add(key.replace("_", ""));
        }

        candidates.add(String.valueOf(baseNumber));
        if (baseNumber == 0) {
            candidates.add("0");
        }
        return removeDuplicateCandidates(candidates);
    }

    private List<String> removeDuplicateCandidates(List<String> candidates) {
        List<String> result = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (seen.add(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private BufferedImage scaleToIcon(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage scaled = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = Math.min((double) ICON_SIZE / width, (double) ICON_SIZE / height);
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        int x = (ICON_SIZE - scaledWidth) / 2;
        int y = (ICON_SIZE - scaledHeight) / 2;
        g2d.drawImage(image, x, y, scaledWidth, scaledHeight, null);
        g2d.dispose();
        return scaled;
    }

    private boolean hasBundledIconSupport(RomHandler handler) {
        int generation = handler.generationOfPokemon();
        if (generation < 6 || generation > 7) {
            return false;
        }
        try (InputStream stream = PokemonIconCache.class.getResourceAsStream(ICON_RESOURCE_ROOT + "25.png")) {
            return stream != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int generationForIcons() {
        return romHandler != null ? romHandler.generationOfPokemon() : 0;
    }

}






