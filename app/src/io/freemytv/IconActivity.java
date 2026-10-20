package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Shelf > hold OK on an app: its own picture for the app's card (shelf and All Apps).
 * Pick an image (the system photo picker, or the pictures saved on the TV), crop it to the card with the remote, save.
 * The picture is saved as files/icons/<package>.png; "Original" deletes it.
 */
public class IconActivity extends Activity {
    static final String EXTRA_PKG = "pkg", EXTRA_LABEL = "label";
    private static final int REQ_PICK = 1, REQ_PERM = 2;
    /** Saved size: 16:9 like the cards, sharp on a 4K TV with the focus zoom. */
    static final int OUT_W = 768, OUT_H = 432;

    private String pkg, label;
    private IconView view;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null) { finish(); return; }
        if (label == null) label = pkg;
        view = new IconView(this);
        setContentView(view);
        view.setFocusable(true);
        view.requestFocus();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view != null && view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    /** files/icons/<package>.png: the card picture the user picked for an app. */
    static File iconFile(Context c, String pkg) {
        return new File(new File(c.getFilesDir(), "icons"), pkg.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
    }

    // MARK: - Picking an image

    /** The system photo picker; on TVs without one, the document picker; else the pictures on the TV. */
    void pickWithSystem() {
        List<Intent> tries = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) tries.add(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"));
        tries.add(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        tries.add(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        tries.add(new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
        for (Intent i : tries) {
            try {
                startActivityForResult(i, REQ_PICK);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        view.notice(Ui.tr("Esta TV não tem seletor de fotos. Abrindo as imagens salvas na TV.",
                "This TV has no photo picker. Opening the pictures saved on the TV instead.",
                "Este TV no tiene selector de fotos. Abriendo las imágenes guardadas en el TV."));
        browseTv();
    }

    private String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** The built-in gallery: every picture in the TV's storage (USB drives too, once the TV indexes them). */
    void browseTv() {
        if (checkSelfPermission(readPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {readPermission()}, REQ_PERM);
            return;
        }
        view.showGallery();
        worker.execute(() -> {
            final List<Uri> uris = new ArrayList<>();
            String[] cols = {MediaStore.Images.Media._ID};
            try (Cursor cur = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cur != null) {
                    while (cur.moveToNext() && uris.size() < 500) {
                        uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)));
                    }
                }
            } catch (RuntimeException ignored) {
            }
            main.post(() -> view.setGallery(uris));
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) browseTv();
        else view.notice(Ui.tr("Sem permissão para ler as fotos da TV.", "No permission to read the TV's pictures.",
                "Sin permiso para leer las imágenes del TV."));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) load(data.getData());
    }

    /** Decodes the picture (at most ~2048 px on its long side, so a huge photo can't run the TV out of memory). */
    void load(final Uri uri) {
        view.busy(true);
        worker.execute(() -> {
            Bitmap b = null;
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                b = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int longSide = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    int sample = 1;
                    while (longSide / (sample * 2) >= 2048) sample *= 2;
                    decoder.setTargetSampleSize(sample);
                });
            } catch (Exception | OutOfMemoryError ignored) {
            }
            final Bitmap result = b;
            main.post(() -> {
                view.busy(false);
                if (result == null) view.notice(Ui.tr("Não deu para abrir essa imagem.", "Couldn't open that picture.",
                        "No se pudo abrir esa imagen."));
                else view.startCrop(result);
            });
        });
    }

    // MARK: - Saving

    /** The card: the crop (or the whole picture on a blurred copy of itself), at OUT_W × OUT_H. */
    static Bitmap render(Bitmap src, RectF crop, boolean whole) {
        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        RectF dst = new RectF(0, 0, OUT_W, OUT_H);
        if (!whole) {
            Rect r = new Rect(Math.round(crop.left), Math.round(crop.top), Math.round(crop.right), Math.round(crop.bottom));
            c.drawBitmap(src, r, dst, p);
            return out;
        }
        // Background: the picture covering the card, shrunk to a few pixels and blown up again (a soft blur), darkened.
        float cover = Math.max((float) OUT_W / src.getWidth(), (float) OUT_H / src.getHeight());
        int tw = Math.max(1, Math.round(src.getWidth() * cover / 24)), th = Math.max(1, Math.round(src.getHeight() * cover / 24));
        Bitmap tiny = Bitmap.createScaledBitmap(src, tw, th, true);
        float bw = tw * 24, bh = th * 24;
        c.drawBitmap(tiny, null, new RectF((OUT_W - bw) / 2, (OUT_H - bh) / 2, (OUT_W + bw) / 2, (OUT_H + bh) / 2), p);
        tiny.recycle();
        c.drawColor(0x66000000);
        float fit = Math.min((float) OUT_W / src.getWidth(), (float) OUT_H / src.getHeight());
        float fw = src.getWidth() * fit, fh = src.getHeight() * fit;
        c.drawBitmap(src, null, new RectF((OUT_W - fw) / 2, (OUT_H - fh) / 2, (OUT_W + fw) / 2, (OUT_H + fh) / 2), p);
        return out;
    }

    void save(final Bitmap src, final RectF crop, final boolean whole) {
        view.busy(true);
        worker.execute(() -> {
            boolean ok = false;
            try {
                Bitmap card = render(src, crop, whole);
                File f = iconFile(this, pkg);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                File tmp = new File(f.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    ok = card.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                ok = ok && tmp.renameTo(f);
                card.recycle();
            } catch (Exception | OutOfMemoryError ignored) {
            }
            HomeApps.customIconChanged(pkg);
            final boolean saved = ok;
            main.post(() -> {
                view.busy(false);
                if (saved) finish();
                else view.notice(Ui.tr("Não deu para salvar.", "Couldn't save it.", "No se pudo guardar."));
            });
        });
    }

    void reset() {
        //noinspection ResultOfMethodCallIgnored
        iconFile(this, pkg).delete();
        HomeApps.customIconChanged(pkg);
        finish();
    }

    // MARK: - The screen (menu, gallery, crop), drawn by hand like the rest of the settings

    final class IconView extends View {
        private static final int MENU = 0, GALLERY = 1, CROP = 2;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;
        private static final int COLS = 5;
        private int mode = MENU, focus;
        private float k = 1;
        private String noticeText;
        private boolean busy;
        private final RectF r = new RectF(), r2 = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap current;   // the app's card as it is now

        // Gallery
        private List<Uri> gallery;
        private int galleryTop;
        private final Map<Uri, Bitmap> thumbs = new HashMap<>();
        private final java.util.Set<Uri> loading = new java.util.HashSet<>();

        // Crop: the picture, the crop box (picture pixels, 16:9), the chip in focus, and whether the arrows adjust
        private Bitmap image;
        private final RectF crop = new RectF();
        private boolean whole, adjusting;
        private int chip;

        IconView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            dash.setStyle(Paint.Style.STROKE);
            dash.setPathEffect(new DashPathEffect(new float[] {14, 10}, 0));
            setBackgroundColor(0xFF0B0D14);
            worker.execute(() -> {
                Bitmap b = HomeApps.cardFor(getContext(), pkg);
                main.post(() -> { current = b; invalidate(); });
            });
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            stroke.setStrokeWidth(4 * k);
            dash.setStrokeWidth(3 * k);
        }

        void notice(String s) { noticeText = s; invalidate(); }
        void busy(boolean b) { busy = b; invalidate(); }

        private boolean hasCustom() { return iconFile(getContext(), pkg).exists(); }

        private String[] menuRows() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Escolher das Fotos", "Choose from Photos", "Elegir de Fotos"));
            l.add(Ui.tr("Imagens salvas na TV", "Pictures on this TV", "Imágenes en este TV"));
            if (hasCustom()) l.add(Ui.tr("Voltar ao ícone original", "Back to the original icon", "Volver al ícono original"));
            return l.toArray(new String[0]);
        }

        void showGallery() {
            mode = GALLERY;
            gallery = null;
            focus = 0;
            galleryTop = 0;
            invalidate();
        }

        void setGallery(List<Uri> uris) {
            gallery = uris;
            if (uris.isEmpty()) notice(Ui.tr("Nenhuma imagem na TV. Envie uma pelo app Send Files to TV ou por um pendrive.",
                    "No pictures on the TV yet. Send one with an app like Send Files to TV, or from a USB drive.",
                    "No hay imágenes en el TV. Envía una con una app como Send Files to TV o desde un USB."));
            invalidate();
        }

        void startCrop(Bitmap b) {
            if (image != null && image != b) image.recycle();
            image = b;
            whole = false;
            adjusting = false;
            chip = 0;
            fitCrop();
            mode = CROP;
            noticeText = null;
            invalidate();
        }

        /** The biggest 16:9 box that fits the picture, centered. */
        private void fitCrop() {
            float iw = image.getWidth(), ih = image.getHeight();
            float cw = Math.min(iw, ih * 16f / 9f), ch = cw * 9f / 16f;
            crop.set((iw - cw) / 2, (ih - ch) / 2, (iw + cw) / 2, (ih + ch) / 2);
        }

        private void clampCrop() {
            float iw = image.getWidth(), ih = image.getHeight();
            float cw = Math.min(crop.width(), Math.min(iw, ih * 16f / 9f));
            cw = Math.max(cw, Math.min(64, iw));
            float ch = cw * 9f / 16f, cx = crop.centerX(), cy = crop.centerY();
            cx = Math.max(cw / 2, Math.min(iw - cw / 2, cx));
            cy = Math.max(ch / 2, Math.min(ih - ch / 2, cy));
            crop.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
        }

        private String[] chips() {
            return new String[] {
                Ui.tr("Mover", "Move", "Mover"),
                Ui.tr("Zoom", "Zoom", "Zoom"),
                whole ? Ui.tr("Recortar", "Crop", "Recortar") : Ui.tr("Imagem inteira", "Whole picture", "Imagen completa"),
                Ui.tr("Salvar", "Save", "Guardar"),
                Ui.tr("Cancelar", "Cancel", "Cancelar"),
            };
        }

        // MARK: Keys

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down || e.getRepeatCount() > 0) return true;
                if (busy) return true;
                if (mode == CROP && adjusting) adjusting = false;
                else if (mode == CROP || mode == GALLERY) { mode = MENU; focus = 0; }
                else finish();
                noticeText = null;
                invalidate();
                return true;
            }
            if (!ok && !arrow) return false;
            if (busy) return true;
            if (ok) {
                if (down && e.getRepeatCount() == 0) activate();
                return true;
            }
            if (!down) return true;
            int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            if (mode == MENU) {
                focus = Math.max(0, Math.min(menuRows().length - 1, focus + dy));
            } else if (mode == GALLERY) {
                if (gallery == null || gallery.isEmpty()) return true;
                int next = focus + dx + dy * COLS;
                if (next >= 0 && next < gallery.size()) focus = next;
                int row = focus / COLS;
                if (row < galleryTop) galleryTop = row;
                if (row > galleryTop + 2) galleryTop = row - 2;
            } else if (mode == CROP) {
                if (!adjusting) {
                    chip = Math.max(0, Math.min(chips().length - 1, chip + dx));
                } else {
                    float speed = Math.min(4, 1 + e.getRepeatCount() / 4f);
                    if (chip == 0) {
                        float step = Math.max(image.getWidth(), image.getHeight()) * 0.02f * speed;
                        crop.offset(dx * step, dy * step);
                    } else if (chip == 1) {
                        int dir = (dx > 0 || dy < 0) ? 1 : -1;   // → or ↑ zooms in
                        float scale = dir > 0 ? 1f / (1 + 0.04f * speed) : 1 + 0.04f * speed;
                        float cx = crop.centerX(), cy = crop.centerY(), cw = crop.width() * scale, ch = cw * 9f / 16f;
                        crop.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
                    }
                    clampCrop();
                }
            }
            invalidate();
            return true;
        }

        private void activate() {
            noticeText = null;
            if (mode == MENU) {
                if (focus == 0) pickWithSystem();
                else if (focus == 1) browseTv();
                else reset();
            } else if (mode == GALLERY) {
                if (gallery != null && focus < gallery.size()) load(gallery.get(focus));
            } else if (mode == CROP) {
                switch (chip) {
                    case 0:
                    case 1:
                        if (whole) { whole = false; fitCrop(); }
                        adjusting = !adjusting;
                        break;
                    case 2:
                        whole = !whole;
                        adjusting = false;
                        if (!whole) fitCrop();
                        break;
                    case 3:
                        save(image, new RectF(crop), whole);
                        break;
                    default:
                        mode = MENU;
                        focus = 0;
                }
            }
            invalidate();
        }

        // MARK: Drawing

        @Override protected void onDraw(Canvas c) {
            Paint title = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
            Paint sub = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            String heading = mode == CROP ? Ui.tr("Recorte o ícone", "Crop the icon", "Recorta el ícono")
                    : mode == GALLERY ? Ui.tr("Imagens na TV", "Pictures on this TV", "Imágenes en el TV")
                    : Ui.tr("Ícone de ", "Icon for ", "Ícono de ") + label;
            c.drawText(TextUtils.ellipsize(heading, new TextPaint(title), 1500 * k, TextUtils.TruncateAt.END).toString(), 120 * k, 130 * k, title);
            if (mode == MENU) drawMenu(c, sub);
            else if (mode == GALLERY) drawGallery(c, sub);
            else drawCrop(c, sub);
            if (noticeText != null) c.drawText(noticeText, 120 * k, 1010 * k, Ui.text(Ui.medium, 26 * k, AMBER));
            if (busy) {
                Paint p = Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(Ui.tr("Um momento…", "One moment…", "Un momento…"), 1800 * k, 130 * k, p);
            }
        }

        private void drawMenu(Canvas c, Paint sub) {
            c.drawText(Ui.tr("Ícone agora", "Current icon", "Ícono actual"), 120 * k, 250 * k, sub);
            r.set(120 * k, 280 * k, 120 * k + 640 * k, 280 * k + 360 * k);
            drawCard(c, current, r);
            String[] rows = menuRows();
            focus = Math.min(focus, rows.length - 1);
            Paint label = Ui.text(Ui.medium, 32 * k, 0xFFFFFFFF);
            for (int i = 0; i < rows.length; i++) {
                r2.set(900 * k, (280 + i * 96) * k, 1800 * k, (280 + i * 96 + 80) * k);
                boolean f = i == focus;
                fill.setColor(f ? 0xFFFFFFFF : 0x1FFFFFFF);
                c.drawRoundRect(r2, 18 * k, 18 * k, fill);
                label.setColor(f ? INK : 0xF2FFFFFF);
                c.drawText(rows[i], r2.left + 32 * k, r2.centerY() + Ui.halfCap(label), label);
            }
            c.drawText(Ui.tr("A imagem é recortada no formato do card (16:9).", "The picture gets cropped to the card's shape (16:9).",
                    "La imagen se recorta con la forma de la tarjeta (16:9)."), 900 * k, (280 + rows.length * 96 + 40) * k, sub);
        }

        private void drawCard(Canvas c, Bitmap b, RectF box) {
            Path clip = new Path();
            clip.addRoundRect(box, 26 * k, 26 * k, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            fill.setColor(0xFF15161C);
            c.drawRect(box, fill);
            if (b != null) c.drawBitmap(b, null, box, bmp);
            c.restore();
        }

        private void drawGallery(Canvas c, Paint sub) {
            if (gallery == null) {
                c.drawText(Ui.tr("Procurando imagens…", "Looking for pictures…", "Buscando imágenes…"), 120 * k, 250 * k, sub);
                return;
            }
            if (gallery.isEmpty()) return;
            float gw = 316, gh = gw * 9 / 16f, gap = 30, left = 120, top = 200;
            for (int row = galleryTop; row < galleryTop + 3; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = row * COLS + col;
                    if (i >= gallery.size()) break;
                    float x = left + col * (gw + gap), y = top + (row - galleryTop) * (gh + 80);
                    r.set(x * k, y * k, (x + gw) * k, (y + gh) * k);
                    if (i == focus) { r.inset(-10 * k, -10 * k); }
                    Uri u = gallery.get(i);
                    Bitmap t = thumbs.get(u);
                    if (t == null) requestThumb(u);
                    fill.setColor(0xFF1A1C24);
                    c.drawRoundRect(r, 16 * k, 16 * k, fill);
                    if (t != null) {
                        float s = Math.max(r.width() / t.getWidth(), r.height() / t.getHeight());
                        float sw = r.width() / s, sh = r.height() / s;
                        Rect src = new Rect(Math.round((t.getWidth() - sw) / 2), Math.round((t.getHeight() - sh) / 2),
                                Math.round((t.getWidth() + sw) / 2), Math.round((t.getHeight() + sh) / 2));
                        Path clip = new Path();
                        clip.addRoundRect(r, 16 * k, 16 * k, Path.Direction.CW);
                        c.save();
                        c.clipPath(clip);
                        c.drawBitmap(t, src, r, bmp);
                        c.restore();
                    }
                    if (i == focus) {
                        stroke.setColor(0xFFFFFFFF);
                        c.drawRoundRect(r, 16 * k, 16 * k, stroke);
                    }
                }
            }
            c.drawText((focus + 1) + " / " + gallery.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels.",
                    "OK elige, Atrás cancela."), 120 * k, 950 * k, sub);
        }

        private void requestThumb(final Uri u) {
            if (!loading.add(u)) return;
            worker.execute(() -> {
                Bitmap t = null;
                try {
                    t = getContentResolver().loadThumbnail(u, new Size(400, 300), null);
                } catch (Exception | OutOfMemoryError ignored) {
                }
                final Bitmap res = t;
                main.post(() -> {
                    if (res != null) {
                        if (thumbs.size() > 60) thumbs.clear();   // keep memory low; visible ones reload
                        thumbs.put(u, res);
                    }
                    loading.remove(u);
                    invalidate();
                });
            });
        }

        private void drawCrop(Canvas c, Paint sub) {
            if (image == null) return;
            // Left: the picture with the crop box. Right: the card as it will look.
            float areaL = 120 * k, areaT = 190 * k, areaW = 1080 * k, areaH = 680 * k;
            float s = Math.min(areaW / image.getWidth(), areaH / image.getHeight());
            float iw = image.getWidth() * s, ih = image.getHeight() * s;
            float ix = areaL + (areaW - iw) / 2, iy = areaT + (areaH - ih) / 2;
            r.set(ix, iy, ix + iw, iy + ih);
            c.drawBitmap(image, null, r, bmp);
            if (!whole) {
                r2.set(ix + crop.left * s, iy + crop.top * s, ix + crop.right * s, iy + crop.bottom * s);
                // Dim outside the box.
                fill.setColor(0x99000000);
                c.drawRect(r.left, r.top, r.right, r2.top, fill);
                c.drawRect(r.left, r2.bottom, r.right, r.bottom, fill);
                c.drawRect(r.left, r2.top, r2.left, r2.bottom, fill);
                c.drawRect(r2.right, r2.top, r.right, r2.bottom, fill);
                stroke.setColor(adjusting ? AMBER : 0xFFFFFFFF);
                c.drawRect(r2, stroke);
                // The band the home shelf shows (its cards are wider than 16:9: about 2.14:1, centered).
                float band = r2.width() / 2.14f;
                dash.setColor(0x99FFFFFF);
                c.drawRect(r2.left, r2.centerY() - band / 2, r2.right, r2.centerY() + band / 2, dash);
            }
            // Preview of the card.
            Bitmap preview = previewBitmap();
            r.set(1290 * k, 230 * k, 1800 * k, 230 * k + 510 * 9 / 16f * k);
            c.drawText(Ui.tr("Prévia", "Preview", "Vista previa"), 1290 * k, 210 * k, sub);
            drawCard(c, preview, r);
            Paint small = Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF);
            if (!whole) c.drawText(Ui.tr("Tracejado: o que aparece na prateleira.", "Dashed lines: what the shelf shows.",
                    "Línea punteada: lo que se ve en el estante."), 1290 * k, r.bottom + 40 * k, small);
            // Chips.
            String[] ch = chips();
            Paint label = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
            float x = 120 * k, y = 900 * k;
            for (int i = 0; i < ch.length; i++) {
                float tw = label.measureText(ch[i]) + 64 * k;
                r2.set(x, y, x + tw, y + 70 * k);
                boolean f = i == chip;
                boolean on = f && adjusting;
                fill.setColor(on ? AMBER : f ? 0xFFFFFFFF : 0x1FFFFFFF);
                c.drawRoundRect(r2, 35 * k, 35 * k, fill);
                label.setColor(f ? INK : 0xF2FFFFFF);
                c.drawText(ch[i], x + 32 * k, r2.centerY() + Ui.halfCap(label), label);
                x += tw + 20 * k;
            }
            String help = adjusting
                    ? (chip == 0 ? Ui.tr("Setas movem o recorte. OK ou Voltar para terminar.", "Arrows move the box. OK or Back when done.",
                            "Las flechas mueven el recorte. OK o Atrás para terminar.")
                            : Ui.tr("↑ → aproximam, ↓ ← afastam. OK ou Voltar para terminar.", "↑ → zoom in, ↓ ← zoom out. OK or Back when done.",
                            "↑ → acercan, ↓ ← alejan. OK o Atrás para terminar."))
                    : Ui.tr("← → escolhem, OK ativa.", "← → pick, OK uses it.", "← → eligen, OK lo usa.");
            if (noticeText == null) c.drawText(help, 120 * k, 1010 * k, sub);
        }

        private Bitmap previewCache;
        private final RectF previewCrop = new RectF();
        private boolean previewWhole;

        private Bitmap previewBitmap() {
            if (previewCache != null && previewCrop.equals(crop) && previewWhole == whole) return previewCache;
            if (previewCache != null) previewCache.recycle();
            previewCache = render(image, crop, whole);
            previewCrop.set(crop);
            previewWhole = whole;
            return previewCache;
        }
    }
}
