package net.ildar.wurm.bot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.shared.constants.PlayerAction;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(description = "Sells items to tokens", abbreviation = "s")
public class SellerBot extends Bot
{
    HashSet<String> items = new HashSet<>();
    HashSet<String> blacklist = new HashSet<>();
    long targetToken = -1;
    int maxSellActions = 3;
    
    Object progressBar;
    Field progressField;
    
    public SellerBot()
    {
        try
        {
            CreationWindow cwindow = WurmHelper.hud.getCreationWindow();
            progressBar = ReflectionUtil.getPrivateField(cwindow, ReflectionUtil.getField(cwindow.getClass(), "progressBar"));
            progressField = ReflectionUtil.getField(progressBar.getClass(), "progress");
        }
        catch(Exception err)
        {
            throw new RuntimeException(err);
        }
        
        registerInputHandler(Inputs.a, this::addItem);
        registerInputHandler(Inputs.ca, input -> clearItems());
        registerInputHandler(Inputs.b, this::addBlacklist);
        registerInputHandler(Inputs.cb, input -> clearBlacklist());
        registerInputHandler(Inputs.st, input -> setTarget());
        registerInputHandler(Inputs.sc, this::setMaxSellActions);
        registerInputHandler(Inputs.gems, input -> setSellGems());
    }
    
    @Override
    public void work() throws Exception
    {
        setTimeout(5000);
        
        List<InventoryMetaItem> toSell = new ArrayList<>();
        while(isActive())
        {
            waitOnPause();
            
            while(isActive() && items.size() == 0 || targetToken < 0 || getProgress() > 0)
                sleep(1000);
            if(!isActive()) break;
            
            toSell.clear();
            for(String itemName: items)
            {
                List<InventoryMetaItem> matches = Utils.getInventoryItems(itemName);
                
                matches:
                for(InventoryMetaItem item: matches)
                {
                    for(String blacklistName: blacklist)
                        if(item.getBaseName().contains(blacklistName))
                            continue matches;
                    toSell.add(item);
                }
            }
            
            int queued = 0;
            long[] actionArgs = new long[]{targetToken};
            for(InventoryMetaItem item: toSell)
            {
                WurmHelper.hud.getWorld().getServerConnection().sendAction(
                    item.getId(),
                    actionArgs,
                    PlayerAction.SELL
                );
                
                if(++queued >= maxSellActions)
                    break;
            }
            
            sleep(timeout);
        }
    }
    
    float getProgress() throws Exception
    {
        return ReflectionUtil.getPrivateField(progressBar, progressField);
    }
    
    void addItem(String[] args)
    {
        if(args.length == 0)
        {
            printInputKeyUsageString(Inputs.a);
            return;
        }
        
        String[] itemNames = String.join(" ", args).split("\\s*,\\s*");
        for(String name: itemNames)
            items.add(name);
        Utils.consolePrint(
            "Selling: %s",
            String.join(", ", items)
        );
    }
    
    void clearItems()
    {
        items.clear();
        Utils.consolePrint("List of items to sell cleared");
    }
    
    void addBlacklist(String[] args)
    {
        if(args.length == 0)
        {
            printInputKeyUsageString(Inputs.a);
            return;
        }
        
        blacklist.add(String.join(" ", args));
        Utils.consolePrint(
            "Selling blacklist: %s",
            String.join(", ", blacklist)
        );
    }
    
    void clearBlacklist()
    {
        blacklist.clear();
        Utils.consolePrint("List of blacklisted items cleared");
    }
    
    void setTarget()
    {
        targetToken = -1;
        try
        {
            PickableUnit pickableUnit = ReflectionUtil.getPrivateField(
                WurmHelper.hud.getSelectBar(),
                ReflectionUtil.getField(WurmHelper.hud.getSelectBar().getClass(), "selectedUnit")
            );
            if(pickableUnit == null || !pickableUnit.getHoverName().contains("settlement token"))
            {
                Utils.consolePrint("Select a deed token!");
                return;
            }
            targetToken = pickableUnit.getId();
            Utils.consolePrint("Target set to %d", targetToken);
        }
        catch (Exception err)
        {
            Utils.consolePrint("Couldn't find deed token");
            err.printStackTrace();
            return;
        }
    }
    
    void setMaxSellActions(String[] args)
    {
        if(args.length != 1)
        {
            printInputKeyUsageString(Inputs.sc);
            return;
        }
        
        try
        {
            int newCount = Integer.parseInt(args[0]);
            if(newCount <= 0) throw new NumberFormatException();
            maxSellActions = newCount;
            
            Utils.consolePrint("Bot will queue %d sell actions", maxSellActions);
        }
        catch(NumberFormatException err)
        {
            Utils.consolePrint("`%s` is not a (positive/nonzero) integer", args[0]);
        }
    }
    
    void setSellGems()
    {
        items.clear();
        blacklist.clear();
        
        items.add("diamond");
        items.add("emerald");
        items.add("opal");
        items.add("ruby");
        addItem(new String[]{"sapphire"}); // trigger console message
        
        // exclude the rare variants
        blacklist.add("star");
        addBlacklist(new String[]{"black opal"});
    }
    
    enum Inputs implements InputKey
    {
        a("Add item to be sold", "name"),
        ca("Clear list of items to sell", ""),
        b("Add blacklisted item name", "name"),
        cb("Clear blacklisted item names", ""),
        st("Set token to sell to", ""),
        sc("Set max queued sell actions", "number"),
        gems("Set up bot to sell common (non-star) gems", "");
        
        String description;
        String usage;
        Inputs(String description, String usage) {
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
