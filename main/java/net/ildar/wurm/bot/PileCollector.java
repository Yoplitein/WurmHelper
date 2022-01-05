package net.ildar.wurm.bot;

import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.client.renderer.gui.ItemListWindow;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.shared.constants.PlayerAction;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(description =
        "Collects piles of items to bulk containers. Default name for target items is \"dirt\"",
        abbreviation = "pc")
public class PileCollector extends Bot {
    private final float MAX_DISTANCE = 4;
    private Set<Long> openedPiles = new HashSet<>();
    private InventoryListComponent targetLc;
    private String containerName = "large crate";
    private int containerCapacity = 300;
    private String targetItemName = "dirt";
    private float minQuality = 0;
    private String customContainer = null;
    
    // items picked up from ground that failed predicates and were dropped back to ground
    private Set<Long> ignoredItems = new HashSet<>();

    public PileCollector() {
        registerInputHandler(PileCollector.InputKey.stn, this::setTargetName);
        registerInputHandler(PileCollector.InputKey.st, this::setTargetInventoryName);
        registerInputHandler(PileCollector.InputKey.stcc, this::setContainerCapacity);
        registerInputHandler(PileCollector.InputKey.mq, this::setMinQuality);
        registerInputHandler(PileCollector.InputKey.cc, this::setCustomContainer);
    }

    @Override
    protected void work() throws Exception {
        setTimeout(500);
        ServerConnectionListenerClass sscc = WurmHelper.hud.getWorld().getServerConnection().getServerConnectionListener();
        Set<Long> pickedUpItems = new HashSet<>();
        while (isActive()) {
            waitOnPause();
            Map<Long, GroundItemCellRenderable> groundItemsMap = ReflectionUtil.getPrivateField(sscc,
                    ReflectionUtil.getField(sscc.getClass(), "groundItems"));
            List<GroundItemCellRenderable> groundItems = groundItemsMap.entrySet().stream().map(Map.Entry::getValue).collect(Collectors.toList());
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            if (groundItems.size() > 0 && targetLc != null) {
                try {
                    for (GroundItemCellRenderable groundItem : groundItems) {
                        GroundItemData groundItemData = ReflectionUtil.getPrivateField(groundItem,
                                ReflectionUtil.getField(groundItem.getClass(), "item"));
                        float itemX = groundItemData.getX();
                        float itemY = groundItemData.getY();
                        long itemID = groundItemData.getId();
                        if ((Math.sqrt(Math.pow(itemX - x, 2) + Math.pow(itemY - y, 2)) <= MAX_DISTANCE)) {
                            final boolean isContainer = shouldSearch(groundItemData.getName().toLowerCase());
                            if (isContainer && !openedPiles.contains(itemID))
                                WurmHelper.hud.sendAction(PlayerAction.OPEN, itemID);
                            else if (!isContainer && groundItemData.getName().contains(targetItemName) && !ignoredItems.contains(itemID)) {
                                // we can't get quality of items on the ground (not synced with client)
                                // so they have to be temporarily moved into player inventory
                                // (not sure why this was also done previously, moving ground items directly works?)
                                WurmHelper.hud.sendAction(PlayerAction.TAKE, itemID);
                                pickedUpItems.add(itemID);
                            }
                        }
                    }
                } catch (ConcurrentModificationException ignored) {}
                
                for(WurmComponent wurmComponent : WurmHelper.getInstance().components) {
                    final boolean isContainerWindow = wurmComponent instanceof ItemListWindow;
                    if (!(isContainerWindow || wurmComponent instanceof InventoryWindow))
                        continue;
                    
                    List<InventoryMetaItem> targetItems;
                    if (isContainerWindow) {
                        InventoryListComponent ilc = ReflectionUtil.getPrivateField(wurmComponent,
                                ReflectionUtil.getField(wurmComponent.getClass(), "component"));
                        if (ilc == null)
                            continue;
                            
                        InventoryMetaItem rootItem = Utils.getRootItem(ilc);
                        if (rootItem == null || (isContainerWindow && !shouldSearch(rootItem.getBaseName().toLowerCase())))
                            continue;
                        
                        openedPiles.add(rootItem.getId());
                        targetItems = Utils.getInventoryItems(ilc, targetItemName);
                    } else {
                        targetItems = Utils.getInventoryItems(targetItemName);
                    }
                    
                    Map<Boolean, List<InventoryMetaItem>> splitItems = targetItems
                        .stream()
                        .collect(Collectors.partitioningBy(item ->
                            item.getBaseName().equals(targetItemName) &&
                            item.getRarity() == 0 &&
                            item.getQuality() >= minQuality
                        ))
                    ;
                    moveToContainers(splitItems.get(true));
                    
                    if (!isContainerWindow)
                        for (InventoryMetaItem item: splitItems.get(false)) {
                            final long itemID = item.getId();
                            if (!pickedUpItems.contains(itemID))
                                continue;
                            
                            pickedUpItems.remove(itemID);
                            ignoredItems.add(itemID);
                            WurmHelper.hud.sendAction(
                                item.getBaseName().matches("dirt|heap of sand") ?
                                    PlayerAction.DROP_AS_PILE :
                                    PlayerAction.DROP
                                ,
                                itemID
                            );
                        }
                }
            }
            sleep(timeout);
        }
    }
    
    private boolean shouldSearch(String itemName) {
        return
            itemName.contains("pile of ") ||
            customContainer != null && itemName.contains(customContainer)
        ;
    }

    private void moveToContainers(List<InventoryMetaItem> targetItems) {
        if (targetItems != null && targetItems.size() > 0) {
            List<InventoryMetaItem> containers = Utils.getInventoryItems(targetLc, containerName);
            if (containers == null || containers.size() == 0) {
                Utils.consolePrint("No target containers!");
                return;
            }
            for(InventoryMetaItem container : containers) {
                List<InventoryMetaItem> containerContents = container.getChildren();
                int itemsCount = 0;
                if (containerContents != null) {
                    for(InventoryMetaItem contentItem : containerContents) {
                        String customName = contentItem.getCustomName();
                        if (customName != null) {
                            try {
                                itemsCount += Integer.parseInt(customName.substring(0, customName.length() - 1));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                if (itemsCount < containerCapacity) {
                    WurmHelper.hud.getWorld().getServerConnection().sendMoveSomeItems(container.getId(), Utils.getItemIds(targetItems));
                    return;
                }
            }
        }
    }

    private void setTargetName(String []input) {
        if (input == null || input.length == 0) {
            printInputKeyUsageString(PileCollector.InputKey.stn);
            return;
        }
        StringBuilder targetName = new StringBuilder(input[0]);
        for (int i = 1; i < input.length; i++) {
            targetName.append(" ").append(input[i]);
        }
        this.targetItemName = targetName.toString();
        Utils.consolePrint("New name for target items is \"" + this.targetItemName + "\"");
        ignoredItems.clear(); // items that didn't match previously may match now
    }

    private void setTargetInventoryName(String []input) {
        WurmComponent wurmComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow);
        if (wurmComponent == null) {
            Utils.consolePrint("Can't find an inventory");
            return;
        }
        try {
            targetLc = ReflectionUtil.getPrivateField(wurmComponent,
                    ReflectionUtil.getField(wurmComponent.getClass(), "component"));
        } catch (IllegalAccessException | NoSuchFieldException e) {
            Utils.consolePrint("Error on configuring the target");
            return;
        }
        if (input != null && input.length != 0) {
            StringBuilder containerName = new StringBuilder(input[0]);
            for (int i = 1; i < input.length; i++) {
                containerName.append(" ").append(input[i]);
            }
            this.containerName = containerName.toString();
        }
        Utils.consolePrint("The target was set with container name - \"" + containerName + "\"");
    }

    private void setContainerCapacity(String []input) {
        if (input == null || input.length == 0) {
            printInputKeyUsageString(PileCollector.InputKey.stcc);
            return;
        }
        try {
            containerCapacity = Integer.parseInt(input[0]);
            Utils.consolePrint("New container capacity is " + containerCapacity);
        }catch (NumberFormatException e) {
            Utils.consolePrint("Wrong value!");
        }
    }
    
    private void setMinQuality(String[] input) {
        if (input == null || input.length == 0) {
            printInputKeyUsageString(PileCollector.InputKey.mq);
            return;
        }
        
        try {
            minQuality = Float.parseFloat(input[0]);
            if (minQuality < 0) throw new NumberFormatException();
            if (minQuality > 0 && minQuality < 1)
                Utils.consolePrint(
                    "Did you mean `%s %2$.1f` to move items >= %2$.1f QL?",
                    PileCollector.InputKey.mq.getName(),
                    minQuality * 100
                );
            Utils.consolePrint("Items with QL >= %.1f will be collected", minQuality);
            ignoredItems.clear(); // items that didn't match previously may match now
        } catch (NumberFormatException err) {
            Utils.consolePrint("`%s` is not a positive real number", input[0]);
        }
    }
    
    private void setCustomContainer(String[] input) {
        if (input == null || input.length == 0)
            customContainer = null;
        else
            customContainer = String.join(" ", input).toLowerCase();
        Utils.consolePrint(
            customContainer == null ?
                "Only piles of items will be searched" :
                "Piles of items and containers named like `%s` will be searched",
            customContainer
        );
    }

    private enum InputKey implements Bot.InputKey {
        stn("Set the name for target items. Default name is \"dirt\"", "name"),
        st("Set the target bulk inventory to put items to. Provide an optional name of containers inside inventory. Default is \"large crate\"", "[name]"),
        stcc("Set the capacity for target container. Default value is 300", "capacity(integer value)"),
        mq("Set minimum quality of items to be collected", "QL(0-100)"),
        cc("Set/clear additional container name to search for target items", "name(or nothing to clear)");

        private String description;
        private String usage;
        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
}
