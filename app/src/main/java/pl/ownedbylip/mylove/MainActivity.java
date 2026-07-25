package pl.ownedbylip.mylove;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MyLoveSync";
    private static final String UNLOCK_PIN = "2702";
    private static final long SYNC_INTERVAL_MS = 15_000L;

    private LetterRepository repository;
    private SupabaseClient supabaseClient;
    private LinearLayout letterList;
    private FrameLayout tabContent;
    private TextView[] tabButtons;
    private int selectedTab;
    private boolean cloudConnected;
    private boolean pairingLoading;
    private String pairingCode;
    private boolean pairingFailed;
    private int ink;
    private int muted;
    private int card;
    private int wine;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private boolean mainScreenActive;
    private boolean syncInProgress;
    private final Runnable periodicSync = new Runnable() {
        @Override
        public void run() {
            if (mainScreenActive) {
                syncFromCloud();
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new LetterRepository(this);
        supabaseClient = new SupabaseClient(this);
        ink = color(com.google.android.material.R.attr.colorOnBackground);
        muted = ContextCompat.getColor(this, isNight() ? R.color.muted_night : R.color.muted);
        card = ContextCompat.getColor(this, isNight() ? R.color.card_night : R.color.card);
        wine = ContextCompat.getColor(this, isNight() ? R.color.wine_light : R.color.wine);

        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        showRoot(buildLockScreen());
    }

    private void showRoot(View root) {
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private View buildLockScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(30), dp(40), dp(30), dp(40));
        root.setBackgroundColor(ContextCompat.getColor(this, isNight() ? R.color.cream_dark : R.color.cream));

        TextView heart = text("♥", 48, wine, Typeface.NORMAL);
        heart.setGravity(Gravity.CENTER);
        root.addView(heart);
        startHeartPulse(heart);

        TextView eyebrow = text(getString(R.string.lock_eyebrow), 12, wine, Typeface.BOLD);
        eyebrow.setLetterSpacing(.18f);
        LinearLayout.LayoutParams eyebrowParams = wrapWrap();
        eyebrowParams.topMargin = dp(18);
        root.addView(eyebrow, eyebrowParams);

        TextView message = text(getString(R.string.lock_message), 16, muted, Typeface.NORMAL);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = wrapWrap();
        messageParams.topMargin = dp(14);
        root.addView(message, messageParams);

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = wrapWrap();
        dotsParams.topMargin = dp(30);
        root.addView(dots, dotsParams);
        TextView[] dotViews = new TextView[4];
        for (int i = 0; i < dotViews.length; i++) {
            TextView dot = new TextView(this);
            dot.setBackground(rounded(0x00000000, dp(20), muted));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(16), dp(16));
            dotParams.setMargins(dp(9), 0, dp(9), 0);
            dots.addView(dot, dotParams);
            dotViews[i] = dot;
        }

        TextView error = text("", 14, wine, Typeface.BOLD);
        error.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        errorParams.topMargin = dp(12);
        root.addView(error, errorParams);

        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setGravity(Gravity.CENTER);
        root.addView(keypad, wrapWrap());

        StringBuilder enteredPin = new StringBuilder();
        View.OnClickListener numberListener = view -> {
            if (enteredPin.length() >= 4) {
                return;
            }
            enteredPin.append((String) view.getTag());
            error.setText("");
            updatePinDots(dotViews, enteredPin.length());
            if (enteredPin.length() == 4) {
                if (UNLOCK_PIN.contentEquals(enteredPin)) {
                    transitionToMain(root);
                } else {
                    error.setText(R.string.wrong_pin);
                    enteredPin.setLength(0);
                    updatePinDots(dotViews, 0);
                }
            }
        };

        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] rowKeys : keys) {
            LinearLayout row = keypadRow();
            for (String key : rowKeys) {
                TextView number = keypadKey(key);
                number.setTag(key);
                number.setOnClickListener(numberListener);
                row.addView(number, keypadKeyParams());
            }
            keypad.addView(row);
        }

        LinearLayout lastRow = keypadRow();
        View spacer = new View(this);
        lastRow.addView(spacer, keypadKeyParams());
        TextView zero = keypadKey("0");
        zero.setTag("0");
        zero.setOnClickListener(numberListener);
        lastRow.addView(zero, keypadKeyParams());
        TextView delete = keypadKey("⌫");
        delete.setContentDescription("Letzte Ziffer löschen");
        delete.setOnClickListener(view -> {
            if (enteredPin.length() > 0) {
                enteredPin.deleteCharAt(enteredPin.length() - 1);
                error.setText("");
                updatePinDots(dotViews, enteredPin.length());
            }
        });
        lastRow.addView(delete, keypadKeyParams());
        keypad.addView(lastRow);
        return root;
    }

    private void transitionToMain(View lockScreen) {
        lockScreen.animate()
                .alpha(0f)
                .scaleX(.96f)
                .scaleY(.96f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    View mainScreen = buildScreen();
                    mainScreen.setAlpha(0f);
                    mainScreen.setTranslationY(dp(28));
                    showRoot(mainScreen);
                    renderLetters();
                    mainScreenActive = true;
                    startPeriodicSync();
                    mainScreen.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(420)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                })
                .start();
    }

    private void startPeriodicSync() {
        syncHandler.removeCallbacks(periodicSync);
        syncFromCloud();
        syncHandler.postDelayed(periodicSync, SYNC_INTERVAL_MS);
    }

    private void syncFromCloud() {
        if (!mainScreenActive || syncInProgress) {
            return;
        }
        syncInProgress = true;
        supabaseClient.syncLetters(new SupabaseClient.SyncCallback() {
            @Override
            public void onSuccess(java.util.List<SupabaseClient.RemoteLetter> letters) {
                int newLetters = repository.upsertRemoteLetters(letters);
                runOnUiThread(() -> {
                    syncInProgress = false;
                    cloudConnected = true;
                    Log.d(TAG, "Synchronisiert: " + letters.size()
                            + " Cloud-Briefe, " + newLetters + " neu");
                    if (selectedTab <= 1) {
                        renderLetters();
                    } else if (selectedTab == 3) {
                        showTab(3, false);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    syncInProgress = false;
                    cloudConnected = false;
                    Log.e(TAG, "Synchronisierung fehlgeschlagen: " + message);
                    if (selectedTab == 3) {
                        showTab(3, false);
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainScreenActive) {
            startPeriodicSync();
        }
    }

    @Override
    protected void onPause() {
        syncHandler.removeCallbacks(periodicSync);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        syncHandler.removeCallbacks(periodicSync);
        repository.close();
        super.onDestroy();
    }

    private void startHeartPulse(View heart) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heart, View.SCALE_X, 1f, 1.14f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heart, View.SCALE_Y, 1f, 1.14f);
        scaleX.setRepeatMode(ValueAnimator.REVERSE);
        scaleY.setRepeatMode(ValueAnimator.REVERSE);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);

        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(scaleX, scaleY);
        pulse.setDuration(850);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        heart.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                // The animation is started immediately below.
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                pulse.cancel();
                view.removeOnAttachStateChangeListener(this);
            }
        });
        pulse.start();
    }

    private LinearLayout keypadRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private TextView keypadKey(String label) {
        TextView key = text(label, label.equals("⌫") ? 23 : 27, ink, Typeface.NORMAL);
        key.setGravity(Gravity.CENTER);
        key.setClickable(true);
        key.setFocusable(true);
        key.setBackground(rounded(card, dp(100), isNight() ? 0xFF4A414D : 0xFFD9C9E6));
        return key;
    }

    private LinearLayout.LayoutParams keypadKeyParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(72));
        params.setMargins(dp(9), dp(7), dp(9), dp(7));
        return params;
    }

    private void updatePinDots(TextView[] dots, int filledCount) {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackground(rounded(
                    i < filledCount ? wine : 0x00000000,
                    dp(20),
                    i < filledCount ? wine : muted));
        }
    }

    private View buildScreen() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(ContextCompat.getColor(this, isNight() ? R.color.cream_dark : R.color.cream));

        tabContent = new FrameLayout(this);
        shell.addView(tabContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(8), dp(5), dp(8), dp(6));
        navigation.setBackgroundColor(card);
        String[] icons = {"♥︎", "✉︎", "●", "•••"};
        String[] labels = {
                getString(R.string.tab_home),
                getString(R.string.tab_letters),
                getString(R.string.tab_memories),
                getString(R.string.tab_more)
        };
        tabButtons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int tabIndex = i;
            TextView tab = text(icons[i] + "\n" + labels[i], 12, muted, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setLines(2);
            tab.setLineSpacing(dp(2), 1f);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setContentDescription(labels[i]);
            tab.setOnClickListener(view -> showTab(tabIndex, true));
            navigation.addView(tab, new LinearLayout.LayoutParams(
                    0, dp(58), 1));
            tabButtons[i] = tab;
        }
        shell.addView(navigation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(69)));
        showTab(0, false);
        return shell;
    }

    private void showTab(int index, boolean animate) {
        selectedTab = index;
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setTextColor(i == index ? wine : muted);
            tabButtons[i].setAlpha(i == index ? 1f : .72f);
        }

        View next;
        if (index == 0) {
            next = buildHomeContent();
        } else if (index == 1) {
            next = buildLettersContent();
        } else if (index == 2) {
            next = buildMemoriesContent();
        } else {
            next = buildMoreContent();
        }
        tabContent.removeAllViews();
        tabContent.addView(next, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (animate) {
            next.setAlpha(0f);
            next.setTranslationY(dp(16));
            next.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }
        renderLetters();
    }

    private View buildHomeContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, isNight() ? R.color.cream_dark : R.color.cream));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(38), dp(24), dp(36));
        scroll.addView(content, matchWrap());

        TextView eyebrow = text(getString(R.string.eyebrow), 12, wine, Typeface.BOLD);
        eyebrow.setLetterSpacing(.16f);
        content.addView(eyebrow);

        TextView greeting = text(getString(R.string.greeting), 42, ink, Typeface.BOLD);
        LinearLayout.LayoutParams greetingParams = wrapWrap();
        greetingParams.topMargin = dp(12);
        content.addView(greeting, greetingParams);

        TextView intro = text(getString(R.string.intro), 18, muted, Typeface.NORMAL);
        intro.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams introParams = wrapWrap();
        introParams.topMargin = dp(12);
        introParams.bottomMargin = dp(18);
        content.addView(intro, introParams);

        TextView signature = text(getString(R.string.signature), 16, wine, Typeface.BOLD);
        signature.setTypeface(Typeface.create("sans", Typeface.BOLD_ITALIC));
        LinearLayout.LayoutParams signatureParams = wrapWrap();
        signatureParams.bottomMargin = dp(42);
        content.addView(signature, signatureParams);

        TextView section = text(getString(R.string.letters_title), 25, ink, Typeface.BOLD);
        content.addView(section);

        TextView hint = text(getString(R.string.offline_hint), 13, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams hintParams = wrapWrap();
        hintParams.topMargin = dp(5);
        hintParams.bottomMargin = dp(18);
        content.addView(hint, hintParams);

        letterList = new LinearLayout(this);
        letterList.setOrientation(LinearLayout.VERTICAL);
        content.addView(letterList, matchWrap());
        return scroll;
    }

    private View buildLettersContent() {
        ScrollView scroll = baseScroll();
        LinearLayout content = pageContent();
        scroll.addView(content, matchWrap());
        content.addView(pageEyebrow(getString(R.string.tab_letters).toUpperCase()));
        TextView title = text(getString(R.string.letters_title), 34, ink, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapWrap();
        titleParams.topMargin = dp(10);
        content.addView(title, titleParams);
        TextView hint = text(getString(R.string.offline_hint), 14, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams hintParams = wrapWrap();
        hintParams.topMargin = dp(7);
        hintParams.bottomMargin = dp(24);
        content.addView(hint, hintParams);
        letterList = new LinearLayout(this);
        letterList.setOrientation(LinearLayout.VERTICAL);
        content.addView(letterList, matchWrap());
        return scroll;
    }

    private View buildMemoriesContent() {
        ScrollView scroll = baseScroll();
        LinearLayout content = pageContent();
        scroll.addView(content, matchWrap());
        content.addView(pageEyebrow(getString(R.string.tab_memories).toUpperCase()));
        TextView title = text(getString(R.string.memories_title), 34, ink, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapWrap();
        titleParams.topMargin = dp(10);
        content.addView(title, titleParams);
        TextView intro = text(getString(R.string.memories_intro), 16, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams introParams = wrapWrap();
        introParams.topMargin = dp(10);
        introParams.bottomMargin = dp(28);
        content.addView(intro, introParams);
        content.addView(placeholderCard("▣", getString(R.string.photos_title),
                getString(R.string.photos_placeholder)));
        content.addView(placeholderCard("○", getString(R.string.timeline_title),
                getString(R.string.timeline_placeholder)));
        letterList = null;
        return scroll;
    }

    private View buildMoreContent() {
        ScrollView scroll = baseScroll();
        LinearLayout content = pageContent();
        scroll.addView(content, matchWrap());
        content.addView(pageEyebrow(getString(R.string.tab_more).toUpperCase()));
        TextView title = text(getString(R.string.more_title), 34, ink, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapWrap();
        titleParams.topMargin = dp(10);
        titleParams.bottomMargin = dp(28);
        content.addView(title, titleParams);
        content.addView(connectionCard());
        content.addView(placeholderCard("◇", getString(R.string.privacy_title),
                getString(cloudConnected ? R.string.privacy_sync : R.string.privacy_text)));
        letterList = null;
        return scroll;
    }

    private View connectionCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        box.setBackground(rounded(card, dp(20), isNight() ? 0xFF4A414D : 0xFFD9C9E6));

        TextView symbol = text("↗", 28, wine, Typeface.NORMAL);
        box.addView(symbol);
        TextView heading = text(getString(R.string.connection_title), 21, ink, Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = wrapWrap();
        headingParams.topMargin = dp(12);
        box.addView(heading, headingParams);

        String status = cloudConnected
                ? getString(R.string.connection_online)
                : getString(R.string.connection_offline);
        TextView statusView = text(status, 15, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams statusParams = wrapWrap();
        statusParams.topMargin = dp(6);
        box.addView(statusView, statusParams);

        TextView explanation = text(getString(R.string.pairing_explanation),
                14, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams explanationParams = wrapWrap();
        explanationParams.topMargin = dp(14);
        box.addView(explanation, explanationParams);

        if (pairingCode != null) {
            String formatted = pairingCode.substring(0, 4) + "  "
                    + pairingCode.substring(4, 8) + "  "
                    + pairingCode.substring(8, 12);
            TextView code = text(formatted, 24, wine, Typeface.BOLD);
            code.setLetterSpacing(.08f);
            code.setGravity(Gravity.CENTER);
            code.setClickable(true);
            code.setFocusable(true);
            code.setContentDescription("Pairing-Code " + pairingCode);
            code.setOnClickListener(view -> copyPairingCode());
            LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            codeParams.topMargin = dp(20);
            box.addView(code, codeParams);

            TextView validity = text(getString(R.string.pairing_validity),
                    12, muted, Typeface.NORMAL);
            validity.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams validityParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            validityParams.topMargin = dp(7);
            box.addView(validity, validityParams);
        } else {
            Button create = new Button(this);
            create.setAllCaps(false);
            create.setText(pairingLoading
                    ? R.string.pairing_loading
                    : R.string.pairing_create);
            create.setEnabled(cloudConnected && !pairingLoading);
            create.setTextColor(Color.WHITE);
            create.setTextSize(15);
            create.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            create.setBackgroundTintList(android.content.res.ColorStateList.valueOf(wine));
            create.setOnClickListener(view -> requestPairingCode());
            LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
            createParams.topMargin = dp(18);
            box.addView(create, createParams);
        }

        if (pairingFailed) {
            TextView error = text(getString(R.string.pairing_error), 13, wine, Typeface.BOLD);
            LinearLayout.LayoutParams errorParams = wrapWrap();
            errorParams.topMargin = dp(12);
            box.addView(error, errorParams);
        }

        LinearLayout.LayoutParams boxParams = matchWrap();
        boxParams.bottomMargin = dp(14);
        box.setLayoutParams(boxParams);
        return box;
    }

    private void requestPairingCode() {
        pairingLoading = true;
        pairingFailed = false;
        showTab(3, false);
        supabaseClient.createPairingCode(new SupabaseClient.PairingCallback() {
            @Override
            public void onSuccess(String code) {
                runOnUiThread(() -> {
                    pairingLoading = false;
                    pairingCode = code;
                    showTab(3, false);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    pairingLoading = false;
                    pairingFailed = true;
                    showTab(3, false);
                });
            }
        });
    }

    private void copyPairingCode() {
        if (pairingCode == null) {
            return;
        }
        Object service = getSystemService(CLIPBOARD_SERVICE);
        if (service instanceof ClipboardManager) {
            ClipboardManager clipboard = (ClipboardManager) service;
            clipboard.setPrimaryClip(ClipData.newPlainText("MyLove Pairing-Code", pairingCode));
            Toast.makeText(this, R.string.pairing_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private ScrollView baseScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, isNight() ? R.color.cream_dark : R.color.cream));
        return scroll;
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(38), dp(24), dp(32));
        return content;
    }

    private TextView pageEyebrow(String value) {
        TextView eyebrow = text(value, 12, wine, Typeface.BOLD);
        eyebrow.setLetterSpacing(.16f);
        return eyebrow;
    }

    private View placeholderCard(String icon, String title, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        box.setBackground(rounded(card, dp(20), isNight() ? 0xFF4A414D : 0xFFD9C9E6));
        TextView symbol = text(icon, 28, wine, Typeface.NORMAL);
        box.addView(symbol);
        TextView heading = text(title, 21, ink, Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = wrapWrap();
        headingParams.topMargin = dp(12);
        box.addView(heading, headingParams);
        TextView body = text(description, 15, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams bodyParams = wrapWrap();
        bodyParams.topMargin = dp(6);
        box.addView(body, bodyParams);
        LinearLayout.LayoutParams boxParams = matchWrap();
        boxParams.bottomMargin = dp(14);
        box.setLayoutParams(boxParams);
        return box;
    }

    private void renderLetters() {
        if (letterList == null) {
            return;
        }
        letterList.removeAllViews();
        java.util.List<Letter> letters = repository.getLetters();
        int limit = selectedTab == 0 ? Math.min(1, letters.size()) : letters.size();
        for (int i = 0; i < limit; i++) {
            Letter letter = letters.get(i);
            letterList.addView(letterCard(letter));
        }
    }

    private View letterCard(Letter letter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setClickable(true);
        box.setFocusable(true);
        box.setBackground(rounded(card, dp(20), isNight() ? 0xFF3A3431 : 0xFFEDE3D9));
        box.setElevation(dp(2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView date = text(letter.dateLabel, 11, wine, Typeface.BOLD);
        date.setLetterSpacing(.12f);
        top.addView(date, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (letter.unread) {
            TextView badge = text(getString(R.string.new_label), 10, Color.WHITE, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(9), dp(4), dp(9), dp(4));
            badge.setBackground(rounded(wine, dp(20), wine));
            top.addView(badge);
        }
        box.addView(top, matchWrap());

        TextView title = text(letter.title, 22, ink, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapWrap();
        titleParams.topMargin = dp(12);
        box.addView(title, titleParams);

        TextView preview = text(letter.preview, 15, muted, Typeface.NORMAL);
        LinearLayout.LayoutParams previewParams = wrapWrap();
        previewParams.topMargin = dp(5);
        box.addView(preview, previewParams);
        box.setOnClickListener(v -> openLetter(letter));

        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        box.setLayoutParams(params);
        return box;
    }

    private void openLetter(Letter letter) {
        repository.markRead(letter.id);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(12), dp(26), dp(4));
        TextView date = text(letter.dateLabel, 11, wine, Typeface.BOLD);
        date.setLetterSpacing(.12f);
        content.addView(date);
        TextView body = text(letter.body, 18, ink, Typeface.NORMAL);
        body.setLineSpacing(dp(6), 1f);
        LinearLayout.LayoutParams bodyParams = wrapWrap();
        bodyParams.topMargin = dp(18);
        bodyParams.bottomMargin = dp(10);
        content.addView(body, bodyParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(letter.title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.setOnDismissListener(ignored -> renderLetters());
        dialog.show();
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setTextColor(wine);
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int color(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
