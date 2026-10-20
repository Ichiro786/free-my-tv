package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The built-in IPTV: playlists (M3U links or Xtream Codes logins), their channels, favorites, recents and the program
 * guide (XMLTV, now and next). Everything stays on the TV: the playlists in the app's preferences, the parsed channels
 * and guide in files, so the next launch is instant and works even while the provider is slow.
 */
final class Iptv {
    static final String UA = "VLC/3.0.20 LibVLC/3.0.20";   // accepted by nearly every IPTV server

    static final class Playlist {
        String id, name, url, epg;
        long loadedAt;

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("name", name).put("url", url).put("epg", epg == null ? "" : epg)
                    .put("loadedAt", loadedAt);
        }

        static Playlist of(JSONObject o) {
            Playlist p = new Playlist();
            p.id = o.optString("id");
            p.name = o.optString("name");
            p.url = o.optString("url");
            p.epg = o.optString("epg");
            p.loadedAt = o.optLong("loadedAt");
            return p;
        }
    }

    static final class Channel {
        String name, url, logo, group, tvgId, ua, referrer;
        int number;

        String key() {
            return url;
        }
    }

    static final class Program {
        long start, stop;
        String title, desc;
    }

    interface Progress { void update(int channels); }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    // MARK: - Playlists

    static List<Playlist> playlists(Context c) {
        List<Playlist> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("playlists", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Playlist.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void savePlaylists(Context c, List<Playlist> list) {
        JSONArray a = new JSONArray();
        try {
            for (Playlist p : list) a.put(p.json());
        } catch (org.json.JSONException ignored) {
        }
        sp(c).edit().putString("playlists", a.toString()).apply();
    }

    static Playlist active(Context c) {
        List<Playlist> list = playlists(c);
        String id = sp(c).getString("active", "");
        for (Playlist p : list) if (p.id.equals(id)) return p;
        return list.isEmpty() ? null : list.get(0);
    }

    static void setActive(Context c, String id) {
        sp(c).edit().putString("active", id).apply();
    }

    static Playlist add(Context c, String name, String url, String epg) {
        List<Playlist> list = playlists(c);
        Playlist p = new Playlist();
        p.id = Long.toString(System.currentTimeMillis(), 36);
        p.name = name == null || name.trim().isEmpty() ? hostOf(url) : name.trim();
        p.url = url.trim();
        p.epg = epg == null ? "" : epg.trim();
        list.add(p);
        savePlaylists(c, list);
        setActive(c, p.id);
        return p;
    }

    /** Xtream Codes login → its M3U (live channels, MPEG-TS) and its XMLTV guide. */
    static Playlist addXtream(Context c, String name, String server, String user, String pass) {
        String base = server.trim();
        if (!base.matches("(?i)^https?://.*")) base = "http://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String q = "username=" + enc(user.trim()) + "&password=" + enc(pass.trim());
        return add(c, name == null || name.trim().isEmpty() ? hostOf(base) : name,
                base + "/get.php?" + q + "&type=m3u_plus&output=ts", base + "/xmltv.php?" + q);
    }

    static void remove(Context c, String id) {
        List<Playlist> list = playlists(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        savePlaylists(c, list);
        //noinspection ResultOfMethodCallIgnored
        channelsFile(c, id).delete();
        //noinspection ResultOfMethodCallIgnored
        epgFile(c, id).delete();
        if (id.equals(sp(c).getString("active", ""))) setActive(c, list.isEmpty() ? "" : list.get(0).id);
    }

    static void rename(Context c, String id, String name) {
        List<Playlist> list = playlists(c);
        for (Playlist p : list) if (p.id.equals(id)) p.name = name;
        savePlaylists(c, list);
    }

    private static void touch(Context c, String id, long at) {
        List<Playlist> list = playlists(c);
        for (Playlist p : list) if (p.id.equals(id)) p.loadedAt = at;
        savePlaylists(c, list);
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    static String hostOf(String url) {
        try {
            String h = new URL(url.matches("(?i)^https?://.*") ? url : "http://" + url).getHost();
            return h == null || h.isEmpty() ? "IPTV" : h;
        } catch (IOException e) {
            return "IPTV";
        }
    }

    // MARK: - Favorites, recents, last channel

    static Set<String> favorites(Context c) {
        return new HashSet<>(sp(c).getStringSet("favorites", Collections.<String>emptySet()));
    }

    static boolean toggleFavorite(Context c, String key) {
        Set<String> f = favorites(c);
        boolean on = !f.remove(key);
        if (on) f.add(key);
        sp(c).edit().putStringSet("favorites", f).apply();
        return on;
    }

    static List<String> recents(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("recents", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void watched(Context c, String key) {
        List<String> r = recents(c);
        r.remove(key);
        r.add(0, key);
        while (r.size() > 30) r.remove(r.size() - 1);
        sp(c).edit().putString("recents", new JSONArray(r).toString()).putString("last", key).apply();
    }

    static String last(Context c) {
        return sp(c).getString("last", "");
    }

    static String aspect(Context c) {
        return sp(c).getString("aspect", "fit");
    }

    static void setAspect(Context c, String v) {
        sp(c).edit().putString("aspect", v).apply();
    }

    // MARK: - Channels: download, parse, cache

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "iptv");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File channelsFile(Context c, String id) {
        return new File(dir(c), "ch-" + id + ".json");
    }

    private static File epgFile(Context c, String id) {
        return new File(dir(c), "epg-" + id + ".json");
    }

    /** The saved channels (instant), or null if this playlist was never loaded. */
    static List<Channel> cached(Context c, Playlist p) {
        File f = channelsFile(c, p.id);
        if (!f.exists()) return null;
        try {
            JSONArray a = new JSONArray(readAll(new FileInputStream(f)));
            List<Channel> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                JSONArray o = a.getJSONArray(i);
                Channel ch = new Channel();
                ch.name = o.optString(0);
                ch.url = o.optString(1);
                ch.logo = emptyNull(o.optString(2));
                ch.group = o.optString(3);
                ch.tvgId = emptyNull(o.optString(4));
                ch.ua = emptyNull(o.optString(5));
                ch.referrer = emptyNull(o.optString(6));
                ch.number = o.optInt(7);
                out.add(ch);
            }
            return out;
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("iptv cache unreadable: " + e);
            return null;
        }
    }

    private static String emptyNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Downloads and parses the playlist (and saves it); returns the channels. Also notes the guide URL it names. */
    static List<Channel> download(Context c, Playlist p, Progress progress) throws IOException {
        HttpURLConnection con = open(p.url, null, null);
        List<Channel> list;
        String[] epgOut = new String[1];
        try (InputStream in = body(con)) {
            list = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16), epgOut, progress);
        } finally {
            con.disconnect();
        }
        if (list.isEmpty()) throw new IOException(Ui.tr("A lista não tem canais", "The playlist has no channels"));
        if ((p.epg == null || p.epg.isEmpty()) && epgOut[0] != null) {
            List<Playlist> all = playlists(c);
            for (Playlist q : all) if (q.id.equals(p.id)) q.epg = epgOut[0];
            savePlaylists(c, all);
            p.epg = epgOut[0];
        }
        save(c, p, list);
        touch(c, p.id, System.currentTimeMillis());
        p.loadedAt = System.currentTimeMillis();
        AppLog.w("iptv playlist \u201c" + p.name + "\u201d: " + list.size() + " channels");
        return list;
    }

    private static void save(Context c, Playlist p, List<Channel> list) {
        File f = channelsFile(c, p.id), part = new File(f.getPath() + ".part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8)) {
            w.write('[');
            for (int i = 0; i < list.size(); i++) {
                Channel ch = list.get(i);
                if (i > 0) w.write(',');
                w.write(new JSONArray().put(ch.name).put(ch.url).put(n(ch.logo)).put(n(ch.group)).put(n(ch.tvgId))
                        .put(n(ch.ua)).put(n(ch.referrer)).put(ch.number).toString());
            }
            w.write(']');
        } catch (IOException e) {
            AppLog.w("iptv cache not saved: " + e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(f);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    /**
     * M3U / M3U8 (extended): #EXTM3U url-tvg, #EXTINF attributes (tvg-id, tvg-name, tvg-logo, group-title, tvg-chno),
     * #EXTGRP and VLC's per-channel options (user agent, referrer). Movies and series of Xtream "m3u_plus" lists are
     * left out: this is live TV.
     */
    static List<Channel> parse(BufferedReader r, String[] epgOut, Progress progress) throws IOException {
        List<Channel> out = new ArrayList<>();
        Map<String, String> attrs = new HashMap<>();
        String title = null, group = null, ua = null, ref = null;
        boolean pending = false;
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (lineNo++ == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) {
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    String k = m.group(1).toLowerCase(Locale.ROOT);
                    if ((k.equals("url-tvg") || k.equals("x-tvg-url")) && epgOut[0] == null && !m.group(2).isEmpty()) {
                        epgOut[0] = m.group(2).split(",")[0].trim();
                    }
                }
            } else if (line.startsWith("#EXTINF")) {
                attrs.clear();
                int comma = commaAfterAttrs(line);
                Matcher m = ATTR.matcher(comma > 0 ? line.substring(0, comma) : line);
                while (m.find()) attrs.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim());
                title = comma > 0 ? line.substring(comma + 1).trim() : "";
                group = attrs.get("group-title");
                ua = attrs.get("http-user-agent");
                ref = attrs.get("http-referrer");
                pending = true;
            } else if (line.startsWith("#EXTGRP:")) {
                if (group == null || group.isEmpty()) group = line.substring(8).trim();
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String opt = line.substring(11).trim();
                int eq = opt.indexOf('=');
                if (eq > 0) {
                    String k = opt.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = opt.substring(eq + 1).trim();
                    if (k.equals("http-user-agent")) ua = v;
                    else if (k.equals("http-referrer") || k.equals("http-referer")) ref = v;
                }
            } else if (!line.startsWith("#")) {
                String url = line;
                String lower = url.toLowerCase(Locale.ROOT);
                boolean vod = lower.contains("/movie/") || lower.contains("/series/")
                        || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi");
                if (!vod && (lower.startsWith("http") || lower.startsWith("rtmp") || lower.startsWith("rtsp") || lower.startsWith("udp"))) {
                    Channel ch = new Channel();
                    String name = title != null && !title.isEmpty() ? title : attrs.get("tvg-name");
                    ch.name = name == null || name.isEmpty() ? hostOf(url) : name;
                    ch.url = url;
                    ch.logo = emptyNull(attrs.get("tvg-logo"));
                    ch.group = group == null || group.isEmpty() ? Ui.tr("Sem categoria", "Uncategorized") : group;
                    ch.tvgId = emptyNull(attrs.get("tvg-id"));
                    ch.ua = emptyNull(ua);
                    ch.referrer = emptyNull(ref);
                    int number = 0;
                    try {
                        String chno = attrs.get("tvg-chno");
                        if (chno != null) number = Integer.parseInt(chno.trim());
                    } catch (NumberFormatException ignored) {
                    }
                    ch.number = number;
                    out.add(ch);
                    if (progress != null && out.size() % 500 == 0) progress.update(out.size());
                    if (out.size() >= 60000) break;
                }
                pending = false;
                title = null;
                group = null;
                ua = null;
                ref = null;
                attrs.clear();
            }
        }
        if (pending) AppLog.w("iptv: last entry had no URL");
        // Numbers: the playlist's own (tvg-chno) where given, the rest counting on from the highest one.
        int next = 1;
        Set<Integer> used = new HashSet<>();
        for (Channel ch : out) if (ch.number > 0) used.add(ch.number);
        for (Channel ch : out) {
            if (ch.number > 0) continue;
            while (used.contains(next)) next++;
            ch.number = next;
            used.add(next++);
        }
        return out;
    }

    /** The comma that ends #EXTINF's attributes (a comma inside a quoted value doesn't count). */
    private static int commaAfterAttrs(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return i;
        }
        return -1;
    }

    static HttpURLConnection open(String url, String ua, String referrer) throws IOException {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua == null ? UA : ua);
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (referrer != null) c.setRequestProperty("Referer", referrer);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();   // http ⇄ https hops too
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException(Ui.tr("redirecionamentos demais", "too many redirects"));
    }

    /** The response body, un-gzipped when the server (or the file itself, like guide.xml.gz) is gzip. */
    static InputStream body(HttpURLConnection c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
        in.mark(4);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        return b0 == 0x1f && b1 == 0x8b ? new BufferedInputStream(new GZIPInputStream(in, 1 << 16), 1 << 16) : in;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = s.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    // MARK: - Program guide (XMLTV): now and next, from 1 h ago to 24 h ahead

    static final class Guide {
        final Map<String, List<Program>> byId = new HashMap<>();   // lowercase tvg-id → programs, in time order
        final Map<String, String> idByName = new HashMap<>();      // lowercase display name → tvg-id
        long loadedAt;

        List<Program> of(Channel ch) {
            List<Program> l = null;
            if (ch.tvgId != null) l = byId.get(ch.tvgId.toLowerCase(Locale.ROOT));
            if (l == null) {
                String id = idByName.get(simplify(ch.name));
                if (id != null) l = byId.get(id);
            }
            return l;
        }

        /** The program on now (index 0) and the next one (index 1); either may be null. */
        Program[] nowNext(Channel ch, long now) {
            Program[] out = new Program[2];
            List<Program> l = of(ch);
            if (l == null) return out;
            for (int i = 0; i < l.size(); i++) {
                Program p = l.get(i);
                if (p.stop <= now) continue;
                if (p.start <= now) {
                    out[0] = p;
                    if (i + 1 < l.size()) out[1] = l.get(i + 1);
                } else {
                    out[1] = p;
                }
                break;
            }
            return out;
        }
    }

    static String simplify(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("\\b(hd|fhd|uhd|4k|sd|hevc)\\b", "")
                .replaceAll("[^a-z0-9]+", "");
    }

    static Guide cachedGuide(Context c, Playlist p) {
        File f = epgFile(c, p.id);
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(readAll(new FileInputStream(f)));
            Guide g = new Guide();
            g.loadedAt = o.optLong("at");
            JSONObject ids = o.getJSONObject("ids");
            java.util.Iterator<String> it = ids.keys();
            long now = System.currentTimeMillis();
            while (it.hasNext()) {
                String id = it.next();
                JSONArray a = ids.getJSONArray(id);
                List<Program> l = new ArrayList<>();
                for (int i = 0; i + 3 < a.length(); i += 4) {
                    Program pr = new Program();
                    pr.start = a.getLong(i);
                    pr.stop = a.getLong(i + 1);
                    pr.title = a.getString(i + 2);
                    pr.desc = a.getString(i + 3);
                    if (pr.stop > now) l.add(pr);
                }
                if (!l.isEmpty()) g.byId.put(id, l);
            }
            JSONObject names = o.optJSONObject("names");
            if (names != null) {
                java.util.Iterator<String> nk = names.keys();
                while (nk.hasNext()) {
                    String n = nk.next();
                    g.idByName.put(n, names.getString(n));
                }
            }
            return g;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Streams the XMLTV file (it can be hundreds of MB, gzipped or not) keeping only this playlist's channels and the
     * programs from an hour ago to a day ahead, then saves that small slice.
     */
    static Guide downloadGuide(Context c, Playlist p, List<Channel> channels) throws Exception {
        Set<String> wantIds = new HashSet<>();
        Set<String> wantNames = new HashSet<>();
        for (Channel ch : channels) {
            if (ch.tvgId != null) wantIds.add(ch.tvgId.toLowerCase(Locale.ROOT));
            wantNames.add(simplify(ch.name));
        }
        Guide g = new Guide();
        long now = System.currentTimeMillis(), from = now - 3600_000L, to = now + 24 * 3600_000L;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        SimpleDateFormat bare = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        bare.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        HttpURLConnection con = open(p.epg, null, null);
        try (InputStream in = body(con)) {
            XmlPullParser x = Xml.newPullParser();
            x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            x.setInput(in, null);
            String channelId = null;
            Program prog = null;
            String progChannel = null;
            int event;
            while ((event = x.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = x.getName();
                    if ("channel".equals(tag)) {
                        channelId = lower(x.getAttributeValue(null, "id"));
                    } else if ("display-name".equals(tag) && channelId != null) {
                        String name = simplify(x.nextText());
                        if (wantNames.contains(name) && !g.idByName.containsKey(name)) {
                            g.idByName.put(name, channelId);
                            wantIds.add(channelId);
                        }
                    } else if ("programme".equals(tag)) {
                        progChannel = lower(x.getAttributeValue(null, "channel"));
                        prog = null;
                        if (progChannel != null && wantIds.contains(progChannel)) {
                            long start = time(fmt, bare, x.getAttributeValue(null, "start"));
                            long stop = time(fmt, bare, x.getAttributeValue(null, "stop"));
                            if (start > 0 && stop > start && stop > from && start < to) {
                                prog = new Program();
                                prog.start = start;
                                prog.stop = stop;
                                prog.title = "";
                                prog.desc = "";
                            }
                        }
                    } else if (prog != null && "title".equals(tag) && prog.title.isEmpty()) {
                        prog.title = x.nextText().trim();
                    } else if (prog != null && "desc".equals(tag) && prog.desc.isEmpty()) {
                        String d = x.nextText().trim();
                        prog.desc = d.length() > 400 ? d.substring(0, 400) + "…" : d;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = x.getName();
                    if ("channel".equals(tag)) channelId = null;
                    else if ("programme".equals(tag)) {
                        if (prog != null) {
                            List<Program> l = g.byId.get(progChannel);
                            if (l == null) g.byId.put(progChannel, l = new ArrayList<>());
                            l.add(prog);
                        }
                        prog = null;
                    }
                }
            }
        } finally {
            con.disconnect();
        }
        for (List<Program> l : g.byId.values()) Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        g.loadedAt = now;
        saveGuide(c, p, g);
        int count = 0;
        for (List<Program> l : g.byId.values()) count += l.size();
        AppLog.w("iptv guide: " + g.byId.size() + " channels, " + count + " programs");
        return g;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static long time(SimpleDateFormat fmt, SimpleDateFormat bare, String s) {
        if (s == null) return 0;
        s = s.trim();
        try {
            if (s.length() >= 20) return fmt.parse(s.substring(0, 14) + " " + s.substring(s.indexOf(' ') + 1).trim()).getTime();
            if (s.length() >= 14) return bare.parse(s.substring(0, 14)).getTime();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static void saveGuide(Context c, Playlist p, Guide g) {
        try {
            JSONObject ids = new JSONObject();
            for (Map.Entry<String, List<Program>> e : g.byId.entrySet()) {
                JSONArray a = new JSONArray();
                for (Program pr : e.getValue()) a.put(pr.start).put(pr.stop).put(pr.title).put(pr.desc);
                ids.put(e.getKey(), a);
            }
            JSONObject names = new JSONObject();
            for (Map.Entry<String, String> e : g.idByName.entrySet()) names.put(e.getKey(), e.getValue());
            File f = epgFile(c, p.id), part = new File(f.getPath() + ".part");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8)) {
                w.write(new JSONObject().put("at", g.loadedAt).put("ids", ids).put("names", names).toString());
            }
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(f);
        } catch (Exception e) {
            AppLog.w("iptv guide not saved: " + e);
        }
    }

    // MARK: - Groups

    /** Groups in the playlist's order, each with its channels. */
    static LinkedHashMap<String, List<Channel>> groups(List<Channel> channels) {
        LinkedHashMap<String, List<Channel>> g = new LinkedHashMap<>();
        for (Channel ch : channels) {
            List<Channel> l = g.get(ch.group);
            if (l == null) g.put(ch.group, l = new ArrayList<>());
            l.add(ch);
        }
        return g;
    }
}
