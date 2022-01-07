package net.ildar.wurm.bot;

import java.util.ArrayList;
import java.util.List;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.InventoryListComponent;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(description =
        "Automatically transfers items to player's inventory from configured bulk storages. " +
        "The n-th  source item will be transferred to the n-th target item",
        abbreviation = "big")
public class BulkItemGetterBot extends Bot
{
    public static boolean closeBMLWindow;
    ArrayList<ItemSpec> specs = new ArrayList<>();
    int selectedSpec = 0;
    
    public BulkItemGetterBot()
    {
        specs.add(new ItemSpec());
        
        registerInputHandler(Inputs.isn, input -> newSpec());
        registerInputHandler(Inputs.isd, input -> deleteSpec());
        registerInputHandler(Inputs.isc, this::selectSpec);
        registerInputHandler(Inputs.isl, input -> listSpecs());
        registerInputHandler(Inputs.ss, input -> setSource(false));
        registerInputHandler(Inputs.ssxy, input -> setSource(true));
        registerInputHandler(Inputs.st, input -> setTarget());
    }
    
    @Override
    public void work() throws Exception
    {
        closeBMLWindow = false;
        setTimeout(15000);
        registerEventProcessor(
            message -> message.contains("That item is already busy"),
            () -> closeBMLWindow = false
        );
        
        while(isActive())
        {
            waitOnPause();
            while (specs.size() <= 0)
                sleep(1000);
            
            for(ItemSpec spec: specs)
            {
                if(spec.target == null) continue;
                
                spec.updateSource(); // ignored when not fixed point
                if(spec.source == null) continue;
                
                closeBMLWindow = true;
                // Utils.consolePrint("moving `%s` => `%s`", spec.source.getDisplayName(), spec.target.getDisplayName());
                WurmHelper.hud.getWorld().getServerConnection().sendMoveSomeItems(spec.target.getId(), new long[]{spec.source.getId()});
                
                int sleeps = 0;
                while(closeBMLWindow && sleeps++ < 50) sleep(100);
                if(closeBMLWindow)
                {
                    Utils.consolePrint("Timed out after 5 seconds waiting for bulk item transfer question");
                    closeBMLWindow = false;
                }
            }
            // Utils.consolePrint("main loop sleep for %dms", timeout);
            sleep(timeout);
        }
    }
    
    void newSpec()
    {
        specs.add(new ItemSpec());
        selectedSpec = specs.size() - 1;
        Utils.consolePrint("Created and selected new spec with index %d", selectedSpec);
    }
    
    void deleteSpec()
    {
        if(specs.size() == 0 || selectedSpec == -1)
        {
            Utils.consolePrint("Don't have any specs (or somehow none selected) to delete!");
            return;
        }
        
        specs.remove(selectedSpec);
        Utils.consolePrint("Deleted spec %d", selectedSpec);
        selectedSpec = specs.size() - 1;
    }
    
    void selectSpec(String[] args)
    {
        if(args == null || args.length != 1)
        {
            printInputKeyUsageString(Inputs.isc);
            return;
        }
        
        final int numSets = specs.size();
        if(numSets == 0)
        {
            Utils.consolePrint(
                "No specs to select! Use %s subcommand to create one",
                Inputs.isn.name()
            );
            return;
        }
        
        try
        {
            int newSelection = Integer.parseInt(args[0]);
            if(newSelection >= numSets)
            {
                Utils.consolePrint(
                    "Only have %d specs, index must be in 0 .. %d",
                    numSets, numSets - 1
                );
                return;
            }
            
            selectedSpec = newSelection;
            Utils.consolePrint("Selected spec %d", selectedSpec);
        }
        catch(NumberFormatException err)
        {
            Utils.consolePrint("`%s` is not an integer", args[0]);
        }
    }
    
    void listSpecs()
    {
        if(specs.size() == 0)
        {
            Utils.consolePrint("No specs yet");
            return;
        }
        
        for(int index = 0; index < specs.size(); index++)
            Utils.consolePrint(
                "%s %d: %s",
                index == selectedSpec ? "*" : " ",
                index,
                String.join(", ", specs.get(index).toString())
            );
    }
    
    void setSource(boolean fixed)
    {
        if(specs.size() == 0 || selectedSpec == -1)
        {
            Utils.consolePrint("Don't have any specs (or somehow none selected)");
            return;
        }
        specs.get(selectedSpec).setSource(fixed);
    }
    
    void setTarget()
    {
        if(specs.size() == 0 || selectedSpec == -1)
        {
            Utils.consolePrint("Don't have any specs (or somehow none selected)");
            return;
        }
        specs.get(selectedSpec).setTarget();
    }
    
    enum Inputs implements Bot.InputKey
    {
        isn("Create a new item spec", ""),
        isd("Delete currently chosen item spec", ""),
        isc("Choose an item spec to operate on", "number"),
        isl("List item specs", ""),
        
        ss("Set the source item for chosen spec (in bulk storage) to what the user is currenly pointing to", ""),
        ssxy("Find source item(s) for chosen spec from a fixed point at current cursor position", ""),
        st("Set the target item for chosen spec to what the user is currently pointing to", ""),
        ;
        
        String description;
        String usage;
        Inputs(String description, String usage)
        {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName()
        {
            return name();
        }

        @Override
        public String getDescription()
        {
            return description;
        }

        @Override
        public String getUsage()
        {
            return usage;
        }
    }
}

class ItemSpec
{
    InventoryMetaItem target = null;
    InventoryMetaItem source = null;
    boolean fixedPointSrc = false;
    int fixedX = -1;
    int fixedY = -1;
    
    public ItemSpec()
    {
        // default to player's inventory
        target = Utils.getRootItem(WurmHelper.hud.getInventoryWindow().getInventoryListComponent());
        Utils.consolePrint("New item spec will move to player inventory");
    }
    
    public void setSource(boolean fixed)
    {
        final int mouseX = WurmHelper.hud.getWorld().getClient().getXMouse();
        final int mouseY = WurmHelper.hud.getWorld().getClient().getYMouse();
        
        source = null;
        fixedX = -1;
        fixedY = -1;
        fixedPointSrc = fixed;
        if(fixed)
        {
            fixedX = mouseX;
            fixedY = mouseY;
            Utils.consolePrint("Current item set will pull items at mouse coordinates %d,%d", fixedX, fixedY);
        }
        else
            updateSource(mouseX, mouseY);
    }
    
    public void setTarget()
    {
        // TODO: readd support for targeting subcontainers, separate command for this case
        target = Utils.getRootItem(Utils.getTargetInventory());
        
        if(target != null)
            Utils.consolePrint("New target is %s", target.getDisplayName());
        else
            Utils.consolePrint("Couldn't find any target containers");
    }
    
    public void updateSource()
    {
        if(!fixedPointSrc)
            return;
        
        updateSource(fixedX, fixedY);
    }
    
    private void updateSource(int x, int y)
    {
        InventoryListComponent inv = Utils.getInventoryAtPoint(x, y);
        List<InventoryMetaItem> items = Utils.getInventoryItemsAtPoint(inv, x, y);
        
        if(items.size() == 0)
        {
            Utils.consolePrint("Couldn't set source: no items found");
            return;
        }
        else if(items.size() > 1)
            Utils.consolePrint("More than one item found, defaulting to the first");
        
        source = items.get(0);
        Utils.consolePrint("Source is now: %s", source.getDisplayName());
    }
    
    @Override
    public String toString()
    {
        final String srcName = fixedPointSrc ?
            String.format(
                "items at %d,%d (currently %s)",
                fixedX,
                fixedY,
                source == null ? "<unset>" : source.getDisplayName()
            ) :
            source != null ? source.getDisplayName() : "<unset>"
        ;
        final String destName = target == null ?
            "<unset>" :
            target.getDisplayName()
        ;
        return String.format("%s => %s", srcName, destName);
    }
}
