package de.collectiontracker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Key bind (default: Right Shift) that opens the settings GUI. Kept in its own file so it is easy to adjust. */
final class OpenKey {
    private static KeyMapping key;

    static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("collection-tracker", "main"));
        key = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.collection-tracker.open", InputConstants.Type.KEYSYM, 344 /* Right Shift */, category));
    }

    static boolean pressed() {
        boolean p = false;
        while (key != null && key.consumeClick()) p = true;
        return p;
    }
}
