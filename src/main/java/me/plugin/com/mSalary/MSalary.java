package me.plugin.com.mSalary;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MSalary extends JavaPlugin implements TabCompleter {

    private LuckPerms luckPerms;
    private Economy economy;
    private Map<String, Integer> salaries;
    private long intervalTicks; // Интервал в тиках
    private BukkitRunnable salaryTask; // Задача для выдачи зарплаты
    private YamlConfiguration lang; // Файл с локализацией

    @Override
    public void onEnable() {
        // Создаем папку lang, если она отсутствует
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Сохраняем языковые файлы по умолчанию
        saveDefaultLangFiles();

        // Загрузка конфигурации
        saveDefaultConfig();
        loadLang(); // Загрузка языкового файла
        loadSalaries();
        loadInterval();

        // Инициализация LuckPerms
        RegisteredServiceProvider<LuckPerms> luckPermsProvider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (luckPermsProvider != null) {
            luckPerms = luckPermsProvider.getProvider();
        } else {
            getLogger().severe(getMessage("luckperms-not-found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Инициализация Vault
        if (!setupEconomy()) {
            getLogger().severe(getMessage("vault-not-found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Регистрация TabCompleter
        this.getCommand("msalary").setTabCompleter(this);

        // Запуск задачи на выдачу зарплаты
        startSalaryTask();
    }

    @Override
    public void onDisable() {
        // Остановка задачи при выключении плагина
        if (salaryTask != null) {
            salaryTask.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("msalary")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                // Проверка разрешения
                if (!sender.hasPermission("msalary.reload")) {
                    sender.sendMessage(getMessage("no-permission"));
                    return true;
                }

                // Перезагрузка конфигурации
                reloadConfig();
                loadLang(); // Перезагрузка языкового файла
                loadSalaries();
                loadInterval();

                // Остановка старой задачи
                if (salaryTask != null) {
                    salaryTask.cancel();
                }

                // Запуск новой задачи с обновленным интервалом
                startSalaryTask();

                sender.sendMessage(getMessage("reload-success"));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Подсказки для команды /msalary
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("reload"); // Единственная подсказка — reload
        }
        return completions;
    }

    private void saveDefaultLangFiles() {
        // Сохраняем английский языковой файл
        saveResource("lang/en.yml", false);

        // Сохраняем русский языковой файл
        saveResource("lang/ru.yml", false);

        // Добавьте другие языковые файлы по мере необходимости
        // saveResource("lang/de.yml", false);
        // saveResource("lang/fr.yml", false);
    }

    private void loadLang() {
        String langName = getConfig().getString("lang", "en"); // По умолчанию английский
        File langFile = new File(getDataFolder(), "lang/" + langName + ".yml");

        // Если файл с выбранным языком отсутствует, используем английский
        if (!langFile.exists()) {
            langFile = new File(getDataFolder(), "lang/en.yml");
            if (!langFile.exists()) {
                saveResource("lang/en.yml", false); // Сохраняем английский файл, если он отсутствует
            }
        }

        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    private String getMessage(String key) {
        String message = lang.getString(key);
        if (message == null) {
            return ChatColor.RED + "Message not found: " + key;
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void loadSalaries() {
        salaries = new HashMap<>();
        for (String key : getConfig().getConfigurationSection("salaries").getKeys(false)) {
            salaries.put(key, getConfig().getInt("salaries." + key));
        }
    }

    private void loadInterval() {
        String timeString = getConfig().getString("time", "10m"); // По умолчанию 10 минут
        intervalTicks = parseTimeStringToTicks(timeString);
        getLogger().info(getMessage("interval-set").replace("{time}", timeString));
    }

    private long parseTimeStringToTicks(String timeString) {
        // Парсим строку времени в тики
        long totalTicks = 0;
        try {
            if (timeString.endsWith("h")) {
                int hours = Integer.parseInt(timeString.substring(0, timeString.length() - 1));
                totalTicks = hours * 60 * 60 * 20; // 1 час = 60 минут = 3600 секунд = 72000 тиков
            } else if (timeString.endsWith("m")) {
                int minutes = Integer.parseInt(timeString.substring(0, timeString.length() - 1));
                totalTicks = minutes * 60 * 20; // 1 минута = 60 секунд = 1200 тиков
            } else if (timeString.endsWith("s")) {
                int seconds = Integer.parseInt(timeString.substring(0, timeString.length() - 1));
                totalTicks = seconds * 20; // 1 секунда = 20 тиков
            } else {
                getLogger().warning(getMessage("invalid-time-format"));
            }
        } catch (NumberFormatException e) {
            getLogger().warning(getMessage("invalid-number-format"));
        }
        return totalTicks;
    }

    private void startSalaryTask() {
        salaryTask = new BukkitRunnable() {
            @Override
            public void run() {
                giveSalaryToOnlinePlayers();
            }
        };
        salaryTask.runTaskTimer(this, 0, intervalTicks); // Запуск задачи с новым интервалом
    }

    private void giveSalaryToOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String group = getPlayerGroup(player.getUniqueId());
            int salary = salaries.getOrDefault(group, salaries.get("default"));

            // Выдача зарплаты через Vault
            if (economy != null) {
                economy.depositPlayer(player, salary);

                // Формируем сообщение с учетом времени
                String message;
                long seconds = intervalTicks / 20; // Переводим тики в секунды
                if (seconds >= 3600) { // 1 час и больше
                    long hours = seconds / 3600;
                    message = getMessage("salary-message-hours")
                            .replace("{salary}", String.valueOf(salary))
                            .replace("{time}", String.valueOf(hours));
                } else if (seconds >= 60) { // 1 минута и больше
                    long minutes = seconds / 60;
                    message = getMessage("salary-message-minutes")
                            .replace("{salary}", String.valueOf(salary))
                            .replace("{time}", String.valueOf(minutes));
                } else { // Меньше минуты
                    message = getMessage("salary-message-seconds")
                            .replace("{salary}", String.valueOf(salary))
                            .replace("{time}", String.valueOf(seconds));
                }

                player.sendMessage(message);
            } else {
                getLogger().warning(getMessage("economy-not-found").replace("{player}", player.getName()));
            }
        }
    }

    private String getPlayerGroup(UUID playerId) {
        User user = luckPerms.getUserManager().getUser(playerId);
        if (user != null) {
            for (Node node : user.getNodes()) {
                if (node.getKey().startsWith("group.")) {
                    return node.getKey().substring(6); // Убираем "group." из начала строки
                }
            }
        }
        return "default";
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }
}