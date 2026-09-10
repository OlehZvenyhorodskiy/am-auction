package de.cubeisland.libMinecraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal replacement for the legacy libMinecraft Translation helper.
 *
 * Important for updated servers: existing language files in the plugin folder can be old.
 * We therefore load bundled defaults first and then overlay external values from
 * plugins/AuctionHousAqua/language/<lang>.ini. Missing keys always fall back to the jar.
 */
public final class Translation
{
    private final Map<String, String> entries;

    private Translation(Map<String, String> entries)
    {
        this.entries = entries;
    }

    public static Translation get(Class<?> owner, String language)
    {
        String resourcePath = "language/" + language + ".ini";
        try (InputStream input = owner.getClassLoader().getResourceAsStream(resourcePath))
        {
            if (input == null)
            {
                return null;
            }
            return fromInputStream(input);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    public static Translation get(File dataFolder, Class<?> owner, String language)
    {
        Translation bundled = get(owner, language);
        if (bundled == null)
        {
            return null;
        }

        Map<String, String> merged = new HashMap<String, String>(bundled.entries);
        if (dataFolder != null)
        {
            File external = new File(dataFolder, "language/" + language + ".ini");
            if (external.isFile())
            {
                try (InputStream input = new FileInputStream(external))
                {
                    merged.putAll(readEntries(input));
                }
                catch (IOException ignored)
                {
                }
            }
        }
        return new Translation(merged);
    }

    private static Translation fromInputStream(InputStream input) throws IOException
    {
        return new Translation(readEntries(input));
    }

    private static Map<String, String> readEntries(InputStream input) throws IOException
    {
        Map<String, String> entries = new HashMap<String, String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || (line.startsWith("[") && line.endsWith("]")))
                {
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator <= 0)
                {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                entries.put(key, value);
            }
        }
        return entries;
    }

    public String translate(String key, Object... params)
    {
        String template = this.entries.get(key);
        if (template == null)
        {
            return "§c<" + key + ">";
        }

        String translated = template;
        if (params != null && params.length > 0)
        {
            try
            {
                translated = String.format(template, params);
            }
            catch (IllegalArgumentException ignored)
            {
                translated = template;
            }
        }
        return translated.replace('&', '§');
    }
}
