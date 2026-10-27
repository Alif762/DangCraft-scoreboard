package my.dangboard;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

public class DangBoard extends JavaPlugin implements Listener, CommandExecutor {

    private static final int LINES = 6;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String[]> last = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private Object eco;
    private Method getBalance;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "shards.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("shards") != null) getCommand("shards").setExecutor(this);

        // Refresh setiap 1 saat (20 tick)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    // ---------- Events ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        update(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        last.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Scoreboard ----------
    private Scoreboard create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("dang", Criteria.DUMMY, "\u00a7f\u00a7lDang\u00a7a\u00a7lCraft");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Sembunyi nombor merah di kanan (Paper terkini), abaikan kalau tak disokong
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", nf).invoke(obj, blank);
        } catch (Throwable ignored) {
        }

        for (int i = 0; i < LINES; i++) {
            Team t = sb.registerNewTeam("l" + i);
            String entry = "\u00a7" + Integer.toHexString(i);
            t.addEntry(entry);
            obj.getScore(entry).setScore(LINES - i);
        }
        p.setScoreboard(sb);
        boards.put(p.getUniqueId(), sb);
        return sb;
    }

    private void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) sb = create(p);
        if (p.getScoreboard() != sb) p.setScoreboard(sb);

        String[] now = build(p);
        String[] old = last.get(p.getUniqueId());
        // Hanya kemas kini baris yang berubah supaya tak berkelip
        for (int i = 0; i < LINES; i++) {
            if (old == null || !now[i].equals(old[i])) {
                Team t = sb.getTeam("l" + i);
                if (t != null) t.setPrefix(now[i]);
            }
        }
        last.put(p.getUniqueId(), now);
    }

    private String[] build(Player p) {
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        return new String[]{
                "",
                "\u00a7a$ \u00a7fMoney: \u00a7a" + fmt(balance(p)),
                "\u00a7d\u2726 \u00a7fShard: \u00a7d" + fmt(shardOf(p)),
                "\u00a7c\u2694 \u00a7fKill: \u00a7c" + kills,
                "\u00a77\u2620 \u00a7fDied: \u00a77" + deaths,
                ""
        };
    }

    // ---------- Money (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void hook() {
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return;
            eco = rsp.getProvider();
            getBalance = c.getMethod("getBalance", OfflinePlayer.class);
        } catch (Throwable ignored) {
        }
    }

    private double balance(Player p) {
        try {
            if (eco == null) hook();
            if (eco != null) return ((Number) getBalance.invoke(eco, p)).doubleValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ---------- Shard ----------
    private long shardOf(Player p) {
        return data.getLong("shards." + p.getUniqueId(), 0);
    }

    private void saveShards() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            getLogger().warning("Tak dapat simpan shards.yml: " + e.getMessage());
        }
    }

    private String fmt(double v) {
        if (v >= 1_000_000_000) return String.format(Locale.US, "%.1fB", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format(Locale.US, "%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "%.1fK", v / 1_000);
        return String.valueOf((long) v);
    }

    // ---------- /shards ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) sender.sendMessage("\u00a7dShard: \u00a7f" + shardOf(p));
            return true;
        }
        if (!sender.hasPermission("dang.shards")) {
            sender.sendMessage("\u00a7cAwak tiada kebenaran.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("\u00a7eGuna: /shards <give|take|set> <pemain> <jumlah>");
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            sender.sendMessage("\u00a7cPemain tu tak online.");
            return true;
        }
        long amt;
        try {
            amt = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("\u00a7cJumlah mesti nombor.");
            return true;
        }
        long cur = data.getLong("shards." + t.getUniqueId(), 0);
        switch (args[0].toLowerCase()) {
            case "give" -> cur += amt;
            case "take" -> cur = Math.max(0, cur - amt);
            case "set" -> cur = Math.max(0, amt);
            default -> {
                sender.sendMessage("\u00a7eGuna: /shards <give|take|set> <pemain> <jumlah>");
                return true;
            }
        }
        data.set("shards." + t.getUniqueId(), cur);
        saveShards();
        update(t);
        sender.sendMessage("\u00a7aShard " + t.getName() + " sekarang: \u00a7f" + cur);
        return true;
    }
        }
