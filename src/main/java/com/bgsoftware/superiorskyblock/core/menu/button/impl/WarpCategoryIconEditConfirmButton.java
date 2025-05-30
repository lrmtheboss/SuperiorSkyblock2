package com.bgsoftware.superiorskyblock.core.menu.button.impl;

import com.bgsoftware.superiorskyblock.api.island.warps.WarpCategory;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.events.args.PluginEventArgs;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEvent;
import com.bgsoftware.superiorskyblock.core.events.plugin.PluginEventsFactory;
import com.bgsoftware.superiorskyblock.core.menu.button.AbstractMenuTemplateButton;
import com.bgsoftware.superiorskyblock.core.menu.button.AbstractMenuViewButton;
import com.bgsoftware.superiorskyblock.core.menu.button.MenuTemplateButtonImpl;
import com.bgsoftware.superiorskyblock.core.menu.view.AbstractIconProviderMenu;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import org.bukkit.event.inventory.InventoryClickEvent;

public class WarpCategoryIconEditConfirmButton extends AbstractMenuViewButton<AbstractIconProviderMenu.View<WarpCategory>> {

    private WarpCategoryIconEditConfirmButton(AbstractMenuTemplateButton<AbstractIconProviderMenu.View<WarpCategory>> templateButton,
                                              AbstractIconProviderMenu.View<WarpCategory> menuView) {
        super(templateButton, menuView);
    }

    @Override
    public void onButtonClick(InventoryClickEvent clickEvent) {
        SuperiorPlayer superiorPlayer = plugin.getPlayers().getSuperiorPlayer(clickEvent.getWhoClicked());

        WarpCategory warpCategory = menuView.getIconProvider();

        PluginEvent<PluginEventArgs.IslandChangeWarpCategoryIcon> event = PluginEventsFactory.callIslandChangeWarpCategoryIconEvent(
                warpCategory.getIsland(), superiorPlayer, warpCategory, menuView.getIconTemplate().build());

        if (event.isCancelled())
            return;

        clickEvent.getWhoClicked().closeInventory();

        Message.WARP_CATEGORY_ICON_UPDATED.send(superiorPlayer);

        warpCategory.setIcon(event.getArgs().icon);
    }

    public static class Builder extends AbstractMenuTemplateButton.AbstractBuilder<AbstractIconProviderMenu.View<WarpCategory>> {

        @Override
        public MenuTemplateButton<AbstractIconProviderMenu.View<WarpCategory>> build() {
            return new MenuTemplateButtonImpl<>(buttonItem, clickSound, commands, requiredPermission,
                    lackPermissionSound, WarpCategoryIconEditConfirmButton.class,
                    WarpCategoryIconEditConfirmButton::new);
        }

    }

}
