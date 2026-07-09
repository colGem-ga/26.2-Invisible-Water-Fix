package com.example.mc308593fix;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Workaround for MC-308593.
 *
 * <p>In 26.2, Mojang changed the animated texture frame upload path from
 * {@code CommandEncoder.writeToTexture(NativeImage, ...)} to a
 * {@code transientMemory().multiUploadStaging(...)} + {@code copyBufferToTexture(...)}
 * path. On some drivers (most visibly Intel HD 4000 / Gen 7) the PBO-based upload
 * path does not correctly honor the {@code GL_UNPACK_IMAGE_HEIGHT} / row-stride
 * state for sub-region copies, so the frame data ends up either transparent
 * (water) or black (lava, fire, lanterns, etc.).</p>
 *
 * <p>This helper re-implements the per-frame upload using the old
 * {@code writeToTexture} approach: for each mip level we create a cropped
 * {@link NativeImage} containing only the desired frame and write it to the
 * frame texture with the synchronous CPU-to-GPU upload path. This restores
 * visibility and transparency while keeping animation fully functional.</p>
 */
public class Mc308593Workaround {
    private static final Field F_THIS$0;
    private static final Field F_UNIQUE_FRAMES;
    private static final Field F_FRAME_ROW_SIZE;
    private static final Field F_BY_MIP_LEVEL;
    private static final Field F_WIDTH;
    private static final Field F_HEIGHT;
    private static final Field F_NAME;
    private static final Constructor<?> CTOR_ANIMATION_STATE;
    private static final boolean CTOR_NEEDS_OUTER;

    static {
        try {
            ClassLoader cl = Mc308593Workaround.class.getClassLoader();
            Class<?> animatedTextureClass = Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture", false, cl);
            Class<?> spriteContentsClass = Class.forName("net.minecraft.client.renderer.texture.SpriteContents", false, cl);
            Class<?> animationStateClass = Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimationState", false, cl);

            F_THIS$0 = animatedTextureClass.getDeclaredField("this$0");
            F_THIS$0.setAccessible(true);

            F_UNIQUE_FRAMES = animatedTextureClass.getDeclaredField("uniqueFrames");
            F_UNIQUE_FRAMES.setAccessible(true);

            F_FRAME_ROW_SIZE = animatedTextureClass.getDeclaredField("frameRowSize");
            F_FRAME_ROW_SIZE.setAccessible(true);

            F_BY_MIP_LEVEL = spriteContentsClass.getDeclaredField("byMipLevel");
            F_BY_MIP_LEVEL.setAccessible(true);

            F_WIDTH = spriteContentsClass.getDeclaredField("width");
            F_WIDTH.setAccessible(true);

            F_HEIGHT = spriteContentsClass.getDeclaredField("height");
            F_HEIGHT.setAccessible(true);

            F_NAME = spriteContentsClass.getDeclaredField("name");
            F_NAME.setAccessible(true);

            // SpriteContents$AnimationState is a non-static inner class of SpriteContents,
            // so at runtime its real constructor has a synthetic outer SpriteContents argument
            // in front of the source-level parameters: (AnimatedTexture, Int2ObjectMap, GpuBufferSlice[]).
            // Some mods or remapped environments may change this, so we accept either form.
            Constructor<?> found = null;
            boolean needsOuter = false;
            for (Constructor<?> c : animationStateClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 3
                    && params[0] == animatedTextureClass
                    && params[1] == Int2ObjectMap.class
                    && params[2].getComponentType() == GpuBufferSlice.class) {
                    found = c;
                    needsOuter = false;
                    break;
                }
                if (params.length == 4
                    && params[0] == spriteContentsClass
                    && params[1] == animatedTextureClass
                    && params[2] == Int2ObjectMap.class
                    && params[3].getComponentType() == GpuBufferSlice.class) {
                    found = c;
                    needsOuter = true;
                    break;
                }
            }
            if (found == null) {
                throw new RuntimeException("No matching AnimationState constructor found");
            }
            CTOR_ANIMATION_STATE = found;
            CTOR_NEEDS_OUTER = needsOuter;
            CTOR_ANIMATION_STATE.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize MC-308593 workaround", t);
        }
    }

    public static SpriteContents.AnimationState createAnimationState(Object animatedTexture, GpuBufferSlice uboSlice, int spriteUboSize) {
        try {
            SpriteContents contents = (SpriteContents) F_THIS$0.get(animatedTexture);
            NativeImage[] byMipLevel = (NativeImage[]) F_BY_MIP_LEVEL.get(contents);
            int width = F_WIDTH.getInt(contents);
            int height = F_HEIGHT.getInt(contents);
            Identifier name = (Identifier) F_NAME.get(contents);
            IntList uniqueFrames = (IntList) F_UNIQUE_FRAMES.get(animatedTexture);
            int frameRowSize = F_FRAME_ROW_SIZE.getInt(animatedTexture);

            GpuDevice device = RenderSystem.getDevice();
            Int2ObjectMap<GpuTextureView> frameTexturesByIndex = new Int2ObjectOpenHashMap<>();
            GpuBufferSlice[] spriteUbosByMip = new GpuBufferSlice[byMipLevel.length];
            CommandEncoder encoder = device.createCommandEncoder();

            for (int i = 0; i < uniqueFrames.size(); i++) {
                int frame = uniqueFrames.getInt(i);
                GpuTexture texture = device.createTexture(
                    () -> name + " animation frame " + frame,
                    5,
                    GpuFormat.RGBA8_UNORM,
                    width,
                    height,
                    1,
                    byMipLevel.length
                );

                int offsetX = (frame % frameRowSize) * width;
                int offsetY = (frame / frameRowSize) * height;

                for (int level = 0; level < byMipLevel.length; level++) {
                    NativeImage fullImage = byMipLevel[level];
                    int frameW = width >> level;
                    int frameH = height >> level;
                    int srcOffsetX = offsetX >> level;
                    int srcOffsetY = offsetY >> level;

                    // Crop the frame out of the full mip-level sprite sheet and upload it
                    // using the synchronous NativeImage write path.
                    NativeImage cropped = new NativeImage(frameW, frameH, false);
                    fullImage.copyRect(cropped, srcOffsetX, srcOffsetY, 0, 0, frameW, frameH, false, false);
                    encoder.writeToTexture(texture, cropped, level, 0, 0, 0);
                    cropped.close();
                }

                frameTexturesByIndex.put(frame, device.createTextureView(texture));
            }

            for (int level = 0; level < byMipLevel.length; level++) {
                spriteUbosByMip[level] = uboSlice.slice((long)(level * spriteUboSize), (long)spriteUboSize);
            }

            Object[] args = CTOR_NEEDS_OUTER
                ? new Object[]{contents, animatedTexture, frameTexturesByIndex, spriteUbosByMip}
                : new Object[]{animatedTexture, frameTexturesByIndex, spriteUbosByMip};
            return (SpriteContents.AnimationState) CTOR_ANIMATION_STATE.newInstance(args);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create AnimationState for MC-308593 workaround", t);
        }
    }
}
