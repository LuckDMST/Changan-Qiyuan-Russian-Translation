package ru.luckdmst.changanqiyuanrus;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Dynamically translates visible native Android text without modifying the target APK. */
public final class TranslatorHook implements IXposedHookLoadPackage {
    private static final String TAG = "QiyuanRus";
    private static final String TARGET_PACKAGE = "com.changan.oushangCos1";
    private static final String MODULE_PACKAGE = "ru.luckdmst.changanqiyuanrus";
    private static final int MAX_CACHE_SIZE = 2000;
    private static final String CACHE_PREFS = "qiyuan_rus_translation_cache";

    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CopyOnWriteArrayList<PendingText>> WAITING =
            new ConcurrentHashMap<>();
    private static final Map<Toast, String> TOAST_SOURCES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> WEB_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<String> WHEEL_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<String> DRAW_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> HOOKED_WHEEL_CLASSES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> APPLYING_TRANSLATION = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPLYING_IMAGE_REPLACEMENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SHOWING_TRANSLATED_TOAST = new ThreadLocal<>();
    private static volatile WeakReference<View> CURRENT_ROOT = new WeakReference<>(null);
    private static volatile SharedPreferences PERSISTENT_CACHE;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        Log.i(TAG, "Installing dynamic native-text translator for " + TARGET_PACKAGE);
        try {
            XposedBridge.hookAllMethods(Application.class, "attach", applicationAttachHook());
            XposedBridge.hookAllMethods(TextView.class, "setText", textHook(false));
            XposedBridge.hookAllMethods(TextView.class, "setHint", textHook(true));
            XposedBridge.hookAllMethods(Activity.class, "onResume", activityScanHook());
            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", activityScanHook());
            XposedBridge.hookAllMethods(View.class, "performClick", profileSaveHook());
            XposedBridge.hookAllMethods(Dialog.class, "show", dialogScanHook());
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupScanHook());
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupScanHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable", imageSetterHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageBitmap", imageSetterHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageResource", imageSetterHook());
            XposedBridge.hookAllMethods(Toast.class, "makeText", toastFactoryHook());
            XposedBridge.hookAllMethods(Toast.class, "setText", toastTextHook());
            XposedBridge.hookAllMethods(Toast.class, "show", toastShowHook());
            XposedBridge.hookAllMethods(WebView.class, "onTouchEvent", webViewTouchHook());
            XposedBridge.hookAllMethods(Canvas.class, "drawText", canvasTextHook());
            installWheelViewHook(lpparam.classLoader);
            Log.i(TAG, "Dynamic TextView, Activity and Dialog scan hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "Dynamic translator hook installation failed", t);
        }
    }

    private static XC_MethodHook applicationAttachHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Context context = (Context) param.args[0];
                    PERSISTENT_CACHE = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to initialize persistent translation cache", t);
                }
            }
        };
    }

    private static XC_MethodHook activityScanHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                scheduleActivityScan(activity, 0);
                scheduleActivityScan(activity, 350);
                scheduleActivityScan(activity, 1500);
                // AgentWeb pages render their HTML after Activity.onResume.
                // Rescan after the initial network load so late WebView text is translated too.
                scheduleActivityScan(activity, 3000);
                scheduleActivityScan(activity, 6000);
                scheduleActivityScan(activity, 10000);
            }
        };
    }

    private static XC_MethodHook profileSaveHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View clicked = (View) param.thisObject;
                if (!hasResourceEntryName(clicked, "bar_tv_other")) return;
                try {
                    int sexId = clicked.getResources().getIdentifier(
                            "tvsex", "id", TARGET_PACKAGE);
                    View candidate = sexId == 0 ? null : clicked.getRootView().findViewById(sexId);
                    if (!(candidate instanceof TextView)) return;
                    TextView sexView = (TextView) candidate;
                    String displayed = sexView.getText().toString().trim();
                    String raw = "Мужской".equals(displayed) ? "男"
                            : "Женский".equals(displayed) ? "女" : null;
                    if (raw == null) return;

                    param.setObjectExtra("qiyuanSexView", sexView);
                    param.setObjectExtra("qiyuanSexDisplay", displayed);
                    APPLYING_TRANSLATION.set(true);
                    sexView.setText(raw);
                } catch (Throwable t) {
                    APPLYING_TRANSLATION.remove();
                    Log.w(TAG, "Unable to restore raw gender before save", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object candidate = param.getObjectExtra("qiyuanSexView");
                Object displayed = param.getObjectExtra("qiyuanSexDisplay");
                if (candidate instanceof TextView && displayed instanceof String) {
                    try {
                        ((TextView) candidate).setText((String) displayed);
                    } finally {
                        APPLYING_TRANSLATION.remove();
                    }
                }
                try {
                    View root = ((View) param.thisObject).getRootView();
                    scheduleViewTreeScan(root, 350);
                    scheduleViewTreeScan(root, 1500);
                    scheduleViewTreeScan(root, 3000);
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to schedule post-click scan", t);
                }
            }
        };
    }

    private static XC_MethodHook dialogScanHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Dialog dialog = (Dialog) param.thisObject;
                try {
                    if (dialog.getWindow() != null) {
                        View root = dialog.getWindow().getDecorView();
                        scheduleViewTreeScan(root, 0);
                        scheduleViewTreeScan(root, 350);
                        scheduleViewTreeScan(root, 1500);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to schedule dialog scan", t);
                }
            }
        };
    }

    private static XC_MethodHook popupScanHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View root = ((PopupWindow) param.thisObject).getContentView();
                    if (root != null) {
                        scheduleViewTreeScan(root, 0);
                        scheduleViewTreeScan(root, 250);
                        scheduleViewTreeScan(root, 1000);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to schedule popup scan", t);
                }
            }
        };
    }

    private static XC_MethodHook imageSetterHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(APPLYING_IMAGE_REPLACEMENT.get())) return;
                ImageView imageView = (ImageView) param.thisObject;
                if (hasResourceEntryName(imageView, "tvsign")) {
                    imageView.post(() -> replaceCheckInBadge(imageView));
                }
            }
        };
    }

    private static XC_MethodHook webViewTouchHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
                MotionEvent event = (MotionEvent) param.args[0];
                if (event.getActionMasked() != MotionEvent.ACTION_UP) return;
                WebView webView = (WebView) param.thisObject;
                webView.postDelayed(() -> scanWebView(webView), 350);
                webView.postDelayed(() -> scanWebView(webView), 1500);
                webView.postDelayed(() -> scanWebView(webView), 3000);
            }
        };
    }

    private static XC_MethodHook toastFactoryHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof Toast) || param.args.length < 2) return;
                String source = resolveToastText(param.args[0], param.args[1]);
                if (source != null && containsChinese(source)) {
                    TOAST_SOURCES.put((Toast) result, source);
                }
            }
        };
    }

    private static XC_MethodHook toastTextHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(SHOWING_TRANSLATED_TOAST.get()) || param.args.length == 0) return;
                String source = resolveToastText(((Toast) param.thisObject).getView() == null
                        ? null : ((Toast) param.thisObject).getView().getContext(), param.args[0]);
                if (source != null && containsChinese(source)) {
                    TOAST_SOURCES.put((Toast) param.thisObject, source);
                }
            }
        };
    }

    private static XC_MethodHook toastShowHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(SHOWING_TRANSLATED_TOAST.get())) return;
                Toast toast = (Toast) param.thisObject;
                String source = TOAST_SOURCES.remove(toast);
                if (source == null || !containsChinese(source)) return;

                String translated = contextualOverride(source);
                if (translated == null) translated = cachedTranslation(source);
                if (translated != null) {
                    setToastText(toast, translated);
                    return;
                }

                param.setResult(null);
                queueToastTranslation(toast, source);
            }
        };
    }

    private static String resolveToastText(Object contextObject, Object value) {
        try {
            if (value instanceof CharSequence) return value.toString().trim();
            if (value instanceof Integer && contextObject instanceof Context) {
                return ((Context) contextObject).getText((Integer) value).toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void setToastText(Toast toast, String text) {
        SHOWING_TRANSLATED_TOAST.set(true);
        try {
            toast.setText(text);
            toast.setDuration(Toast.LENGTH_LONG);
        } finally {
            SHOWING_TRANSLATED_TOAST.remove();
        }
    }

    private static void queueToastTranslation(Toast toast, String source) {
        TRANSLATION_EXECUTOR.execute(() -> {
            String translated = null;
            try {
                translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    cacheTranslation(source, translated);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Toast translation failed for " + abbreviate(source), t);
            }
            final String result = translated == null || translated.isEmpty() ? source : translated;
            new Handler(Looper.getMainLooper()).post(() -> {
                setToastText(toast, result);
                SHOWING_TRANSLATED_TOAST.set(true);
                try {
                    toast.show();
                } finally {
                    SHOWING_TRANSLATED_TOAST.remove();
                }
            });
        });
    }

    private static void scheduleActivityScan(Activity activity, long delayMs) {
        try {
            View root = activity.getWindow().getDecorView();
            CURRENT_ROOT = new WeakReference<>(root);
            scheduleViewTreeScan(root, delayMs);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to schedule activity scan", t);
        }
    }

    private static void scheduleViewTreeScan(View root, long delayMs) {
        root.postDelayed(() -> scanViewTree(root), delayMs);
    }

    private static void scanViewTree(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            queueCurrentValue(textView, false);
            queueCurrentValue(textView, true);
        }

        if (view instanceof ImageView && hasResourceEntryName(view, "ivlogo")) {
            replaceLoginLogo((ImageView) view);
        }

        if (view instanceof ImageView && hasResourceEntryName(view, "tvsign")) {
            replaceCheckInBadge((ImageView) view);
        }

        String className = view.getClass().getName();
        if (className.endsWith(".WheelView") || className.contains("WheelView")) {
            installWheelViewHook(view.getClass(), view);
        }

        if (view instanceof WebView) {
            scanWebView((WebView) view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scanViewTree(group.getChildAt(i));
            }
        }
    }

    private static void queueCurrentValue(TextView view, boolean hint) {
        CharSequence value = hint ? view.getHint() : view.getText();
        if (value == null) return;
        String source = value.toString().trim();
        if (containsChinese(source) || contextualOverride(source) != null) {
            queueTranslation(view, source, hint);
        }
    }

    private static boolean hasResourceEntryName(View view, String expected) {
        if (view.getId() == View.NO_ID) return false;
        try {
            return expected.equals(view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void replaceLoginLogo(ImageView imageView) {
        try {
            Context moduleContext = imageView.getContext().createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            int logoId = moduleContext.getResources().getIdentifier(
                    "changan_qiyuan_logo", "drawable", MODULE_PACKAGE);
            if (logoId == 0) {
                Log.w(TAG, "Replacement logo resource not found");
                return;
            }
            Drawable logo = moduleContext.getResources().getDrawable(logoId, moduleContext.getTheme());
            imageView.setImageDrawable(logo);
            imageView.setAdjustViewBounds(true);
        } catch (Throwable t) {
            Log.w(TAG, "Login logo replacement failed", t);
        }
    }

    private static void replaceCheckInBadge(ImageView imageView) {
        try {
            int width = imageView.getWidth();
            int height = imageView.getHeight();
            float density = imageView.getResources().getDisplayMetrics().density;
            if (width <= 0) width = Math.round(81 * density);
            if (height <= 0) height = Math.round(22 * density);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(30, 76, 130));
            canvas.drawRoundRect(new RectF(0, 0, width, height), height / 2f, height / 2f, paint);

            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            String label = "CHECKED IN";
            float textSize = height * 0.36f;
            paint.setTextSize(textSize);
            float availableWidth = width * 0.82f;
            float measuredWidth = paint.measureText(label);
            if (measuredWidth > availableWidth) {
                paint.setTextSize(textSize * availableWidth / measuredWidth);
            }
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(label, width / 2f, baseline, paint);
            APPLYING_IMAGE_REPLACEMENT.set(true);
            try {
                imageView.setImageBitmap(bitmap);
                imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            } finally {
                APPLYING_IMAGE_REPLACEMENT.remove();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Check-in badge replacement failed", t);
        }
    }

    private static XC_MethodHook textHook(final boolean hint) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(APPLYING_TRANSLATION.get())) return;

                TextView view = (TextView) param.thisObject;
                CharSequence value = hint ? view.getHint() : view.getText();
                if (value == null) return;

                String source = value.toString().trim();
                if (!containsChinese(source) && contextualOverride(source) == null) return;
                queueTranslation(view, source, hint);
            }
        };
    }

    private static void queueTranslation(TextView view, String source, boolean hint) {
        String contextual = contextualOverride(source);
        if (contextual != null) {
            applyIfCurrent(view, source, contextual, hint);
            return;
        }

        String cached = cachedTranslation(source);
        if (cached != null) {
            applyIfCurrent(view, source, cached, hint);
            return;
        }

        PendingText pending = new PendingText(view, source, hint);
        CopyOnWriteArrayList<PendingText> first = new CopyOnWriteArrayList<>();
        first.add(pending);
        CopyOnWriteArrayList<PendingText> existing = WAITING.putIfAbsent(source, first);
        if (existing != null) {
            existing.add(pending);
            return;
        }

        TRANSLATION_EXECUTOR.execute(() -> {
            String translated = null;
            try {
                translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    cacheTranslation(source, translated);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Translation failed for " + abbreviate(source), t);
            }

            List<PendingText> requests = WAITING.remove(source);
            if (translated == null || requests == null) return;
            for (PendingText request : requests) {
                TextView target = request.view.get();
                if (target != null) {
                    applyIfCurrent(target, request.source, translated, request.hint);
                }
            }
        });
    }

    private static String contextualOverride(String source) {
        // Short navigation and action labels need UI context that a generic
        // translator cannot infer from an isolated word.
        if ("服务".equals(source)) return "Сервисы";
        if ("购车".equals(source)) return "Авто";
        if ("去授权".equals(source)) return "Разрешить";
        if ("长安启源".equals(source)) return "CHANGAN QIYUAN";
        if ("长安启源 has been running in the background".equals(source)) {
            return "Приложение работает в фоновом режиме";
        }
        if ("建议使用购车时在经销商登记的手机号码".equals(source)) return "Телефон, указанный при покупке";
        if ("请输入图形验证码".equals(source)) return "Код с картинки";
        if ("请输入手机验证码".equals(source)) return "Код из SMS";
        if ("获取验证码".equals(source)) return "Получить код";
        if ("未注册用户登录后自动注册".equals(source)) return "Новым — авторегистрация";
        if ("密码登录".equals(source)) return "Пароль";
        if ("登录".equals(source)) return "Войти";
        if ("微信登录".equals(source)) return "WeChat";
        if ("QQ登录".equals(source)) return "QQ";
        if ("联系我们".equals(source) || "Связаться с нами".equals(source)) return "Поддержка";
        if ("实名认证".equals(source) || "Аутентификация по настоящему имени".equals(source)) return "Верификация";
        if ("遥控车型".equals(source) || "远程控车".equals(source) || "遥控车模".equals(source)
                || "Модель автомобиля с дистанционным управлением".equals(source)) return "Пульт авто";
        if ("紧急钥匙".equals(source) || "Экстренный ключ".equals(source)) return "SOS-ключ";
        if ("发现".equals(source)) return "Обзор";
        if ("商城".equals(source)) return "Магазин";
        if ("我的".equals(source)) return "Профиль";
        if ("推荐".equals(source)) return "Рекомендуем";
        if ("看车".equals(source)) return "Автомобили";
        if ("粉丝".equals(source) || "我的粉丝".equals(source)
                || "мои фанаты".equalsIgnoreCase(source)) return "Подписчики";
        if ("关注".equals(source) || "我的关注".equals(source)
                || "моя забота".equalsIgnoreCase(source)) return "Подписки";
        if ("发布".equals(source)) return "Публикации";
        if ("喜欢".equals(source)) return "Лайки";
        if ("元宝".equals(source) || "源宝".equals(source)) return "Бонусы";
        if ("定级积分".equals(source)) return "Рейтинг";
        if ("成长中心".equals(source)) return "Уровень";
        if ("说明".equals(source)) return "Справка";
        if ("定级积分记录".equals(source)) return "История рейтинга";
        if ("0定级积分".equals(source)) return "Рейтинг: 0";
        if ("定级积分20可升级LV1".equals(source)) return "Для уровня LV1 нужно 20 баллов";
        if ("挂件".equals(source)) return "Украшения";
        if ("车主店铺".equals(source)) return "Магазин";
        if ("同城活动".equals(source)) return "События";
        if ("我的卡券".equals(source)) return "Купоны";
        if ("我的订单".equals(source)) return "Заказы";
        if ("在线评价".equals(source)) return "Отзывы";
        if ("我的活动".equals(source)) return "Активность";
        if ("我的圈子".equals(source)) return "Сообщества";
        if ("我的爱车".equals(source)) return "Мой автомобиль";
        if ("快速控车".equals(source)) return "Быстрое управление автомобилем";
        if ("解锁".equals(source)) return "Открыть";
        if ("闭锁".equals(source)) return "Закрыть";
        if ("车窗".equals(source)) return "Окна";
        if ("寻车".equals(source)) return "Найти автомобиль";
        if ("智能空调".equals(source)) return "Умный климат";
        if ("速冷模式".equals(source)) return "Быстрое охлаждение";
        if ("未充电".equals(source)) return "Не заряжается";
        if ("有新版本软件等待更新".equals(source)) return "Доступно обновление";
        if (source.startsWith("启源伙伴总量：")) {
            return "Всего партнёров Changan Qiyuan: " + source.substring("启源伙伴总量：".length());
        }
        if ("我要申请".equals(source)) return "Подать заявку";
        if ("我的发布".equals(source)) return "Мои публикации";
        if ("我的帖子".equals(source)) return "Мои записи";
        if ("我的草稿".equals(source)) return "Мои черновики";
        if ("我的评论".equals(source)) return "Комментарии";
        if ("管理".equals(source)) return "Управление";
        if ("全选".equals(source)) return "Выбрать все";
        if ("删除".equals(source)) return "Удалить";
        if ("完成".equals(source)) return "Готово";
        if ("暂无内容~".equals(source)) return "Пока нет записей~";
        if ("我的点赞".equals(source)) return "Понравившееся";
        if ("帖子".equals(source)) return "Публикации";
        if ("资讯".equals(source)) return "Материалы";
        if ("我的历程".equals(source)) return "Моя история";
        if ("我的主页".equals(source)) return "Моя страница";
        if ("暂无简介".equals(source)) return "Нет описания";
        if ("我发布的帖子".equals(source)) return "Мои публикации";
        if ("编辑个人信息".equals(source)) return "Редактировать профиль";
        if ("现居:重庆-潼南区".equals(source)) return "Живёт: Чунцин - Туннань";
        if ("来自:重庆-潼南区".equals(source)) return "Родной город: Чунцин - Туннань";
        if ("个人资料已完善，源气“新形象”秀出精彩吧".equals(source)) {
            return "Профиль заполнен. Покажите свой новый образ в Changan Qiyuan";
        }
        if ("成功注册启源APP，成为启源粉丝大家庭一员".equals(source)) {
            return "Регистрация завершена. Добро пожаловать в сообщество Changan Qiyuan";
        }
        if (source.matches("\\d{4}年")) return source.substring(0, 4) + " г.";
        if (source.matches("\\d{1,2}月")) {
            try {
                int month = Integer.parseInt(source.substring(0, source.length() - 1));
                String[] months = {"янв.", "февр.", "марта", "апр.", "мая", "июня",
                        "июля", "авг.", "сент.", "окт.", "нояб.", "дек."};
                if (month >= 1 && month <= 12) return months[month - 1];
            } catch (NumberFormatException ignored) {
            }
        }
        if (source.matches("\\d+小时前")) {
            try {
                int hours = Integer.parseInt(source.substring(0, source.length() - 3));
                int lastTwo = hours % 100;
                int last = hours % 10;
                String unit = last == 1 && lastTwo != 11 ? "час"
                        : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "часа"
                        : "часов";
                return hours + " " + unit + " назад";
            } catch (NumberFormatException ignored) {
            }
        }
        if ("恭喜您，个人信息完善成功".equals(source)) {
            return "Поздравляем, профиль успешно заполнен";
        }
        if ("恭喜您，启源 APP个人信息完善成功！".equals(source)) {
            return "Поздравляем! Профиль в приложении Changan Qiyuan успешно заполнен.";
        }
        if ("认证指导".equals(source)) return "Инструкция по верификации";
        if ("实名认证".equals(source)) return "Верификация";
        if ("拼搭指导/车辆操控".equals(source)) return "Комплектация / Управление автомобилем";
        if ("金融试算".equals(source)) return "Рассчитать кредит";
        if ("门店".equals(source)) return "Автосалоны";
        if ("预约试驾".equals(source)) return "Записаться на тест-драйв";
        if ("立即订购".equals(source)) return "Заказать сейчас";
        if ("我买了一辆新车".equals(source)) return "Я купил новый автомобиль";
        if ("我买了一辆二手车".equals(source)) return "Я купил подержанный автомобиль";
        if ("需要进行实名认证".equals(source)) return "Требуется подтвердить личность";
        if ("新车实名认证".equals(source)) return "Верификация нового автомобиля";
        if ("二手车实名认证".equals(source)) return "Верификация подержанного автомобиля";
        if ("我的收藏".equals(source) || "моя коллекция".equalsIgnoreCase(source)) return "Избранное";
        if ("我的宝藏".equals(source) || "Мое сокровище-источник".equalsIgnoreCase(source)
                || "Моё сокровище-источник".equalsIgnoreCase(source)) return "История Юаньбао";
        if ("搜索更多精彩活动".equals(source)) return "Поиск интересных событий";
        if ("启源伙伴".equals(source) || "长安启源伙伴".equals(source)) return "Партнёры";
        if ("已签到".equals(source)) return "Отмечено";
        if ("设置".equals(source)) return "Настройки";
        if ("个人资料".equals(source)) return "Личные данные";
        if ("隐私设置".equals(source)) return "Приватность";
        if ("账号与安全".equals(source)) return "Аккаунт и безопасность";
        if ("权限管理".equals(source)) return "Разрешения";
        if ("个性装扮".equals(source)) return "Оформление";
        if ("地址管理".equals(source)) return "Адреса";
        if ("隐私协议".equals(source)) return "Политика конфиденциальности";
        if ("清除缓存".equals(source)) return "Очистить кеш";
        if ("版本".equals(source)) return "Версия";
        if ("未授权".equals(source)) return "Не разрешено";
        if ("已授权".equals(source)) return "Разрешено";
        if ("位置".equals(source)) return "Геолокация";
        if ("长安启源申请获取电话权限".equals(source)) {
            return "Changan Qiyuan запрашивает доступ к телефону";
        }
        if ("电话权限用于联系客户、服务人员等需要拨打电话的功能。".equals(source)) {
            return "Доступ к телефону необходим для звонков клиентам и сотрудникам сервиса.";
        }
        if ("开启权限".equals(source)) return "Доступ к телефону";
        if ("您拒绝了《电话》权限，无法使用功能.".equals(source)) {
            return "Доступ к телефону запрещён. Функция недоступна.";
        }
        if ("相机".equals(source)) return "Камера";
        if ("麦克风".equals(source)) return "Микрофон";
        if ("照片和视频".equals(source)) return "Фото и видео";
        if ("通知".equals(source)) return "Уведомления";
        if ("通讯录".equals(source)) return "Контакты";
        if ("附近设备".equals(source)) return "Устройства рядом";
        if ("账号密码".equals(source) || "账户密码".equals(source)) return "Пароль";
        if ("未设置".equals(source)) return "Не задан";
        if ("我的消息".equals(source)) return "Сообщения";
        if ("聊天列表".equals(source)) return "Чаты";
        if ("输入关键字搜索".equals(source)) return "Поиск";
        if ("官方通知".equals(source)) return "Официальные";
        if ("个人互动".equals(source)) return "Личные уведомления";
        if ("用车消息".equals(source)) return "Автомобиль";
        if ("智能客服".equals(source)) return "Онлайн-помощник";
        if ("智能客服小安".equals(source)) return "Помощник Сяоань";
        if ("驾驶指南".equals(source)) return "Руководство водителя";
        if ("预约服务".equals(source)) return "Запись на сервис";
        if ("门店信息".equals(source)) return "Дилерские центры";
        if ("维保记录".equals(source)) return "История обслуживания";
        if ("热点问题".equals(source)) return "Популярные вопросы";
        if ("换一换".equals(source)) return "Другие";
        if ("如何远程控车".equals(source)) return "Как управлять автомобилем дистанционно?";
        if ("充电功率是多少".equals(source)) return "Какая мощность зарядки?";
        if ("推荐购车赢豪礼".equals(source)) return "Подарки за рекомендацию автомобиля";
        if ("如何查询车辆定位".equals(source)) return "Как посмотреть местоположение автомобиля?";
        if ("蓝牙钥匙如何分享".equals(source)) return "Как поделиться Bluetooth-ключом?";
        if ("您好，请问有什么可以帮助您？".equals(source)) return "Здравствуйте! Чем я могу помочь?";
        if ("转人工".equals(source)) return "Оператор";
        if ("服务评价".equals(source)) return "Оценка сервиса";
        if ("车机升级".equals(source)) return "Обновление системы";
        if ("网点查询".equals(source)) return "Поиск дилера";
        if ("推荐有礼".equals(source)) return "Бонус за рекомендацию";
        if ("车机流量查询".equals(source)) return "Трафик автомобиля";
        if ("请输入消息...".equals(source)) return "Введите сообщение...";
        if ("您可能还想问".equals(source)) return "Возможно, вас также интересует";
        if ("以上为历史消息".equals(source)) return "Выше показана история сообщений";
        if ("控车码如何使用".equals(source)) return "Как использовать код управления?";
        if ("无法远程控车怎么办".equals(source)) return "Что делать, если дистанционное управление не работает?";
        if ("蓝牙钥匙如何使用".equals(source)) return "Как пользоваться Bluetooth-ключом?";
        if ("指纹控车功能如何使用".equals(source)) return "Как использовать управление по отпечатку пальца?";
        if ("会员认证/实名认证相关咨询".equals(source)) return "Подтверждение аккаунта и личности";
        if ("一.".equals(source)) return "1. ";
        if ("二.".equals(source)) return "2. ";
        if ("启源车主：首任、二手车主在您完成车联网实名认证绑定车辆后，使用车联网认证手机号登录启源APP".equals(source)) {
            return "Владельцы Changan Qiyuan: после подтверждения личности и привязки автомобиля войдите в приложение по номеру, использованному при регистрации автомобиля";
        }
        if ("，通过【".equals(source)) return ", затем [";
        if ("点击此处".equals(source)) return "нажмите здесь";
        if ("】或点击【爱车】界面，即可使用".equals(source)) {
            return "] или откройте раздел [Мой автомобиль], чтобы использовать";
        }
        if ("APP远程控车或蓝牙钥匙控车".equals(source)) {
            return " дистанционное управление или Bluetooth-ключ";
        }
        if ("功能。".equals(source)) return ".";
        if ("欧尚车主：".equals(source)) return "Владельцы Oshan: ";
        if ("使用手机应用商点下载【".equals(source)) return "Скачайте в магазине приложений [";
        if ("引力域APP".equals(source)) return "приложение Yinliyu";
        if ("】后，登录".equals(source)) return "], затем войдите по ";
        if ("车联网认证手机号".equals(source)) return "номеру регистрации автомобиля. ";
        if ("绑定爱车后，可以实现手机远程控车或蓝牙钥匙控车功能。".equals(source)) {
            return "После привязки автомобиля станут доступны дистанционное управление и Bluetooth-ключ.";
        }
        if ("您好，由于长时间没有接收到您的消息，系统自动结束会话。如果还有需要，欢迎随时联系我们。".equals(source)) {
            return "Сеанс завершён из-за отсутствия сообщений. Если потребуется помощь, обратитесь к нам снова.";
        }
        if ("在线客服".equals(source)) return "Поддержка";
        if ("客服热线".equals(source)) return "Горячая линия";
        if ("车辆相关".equals(source)) return "Вопросы по автомобилю";
        if ("售后服务".equals(source)) return "Сервис";
        if ("车控问题".equals(source)) return "Управление";
        if ("OTA升级".equals(source)) return "Обновления";
        if ("APP功能".equals(source)) return "Приложение";
        if ("社区/活动".equals(source)) return "Сообщество";
        if ("用户账号".equals(source)) return "Учётная запись";
        if ("启源商城".equals(source)) return "Магазин Changan Qiyuan";
        if ("启源伙伴的推荐奖励怎么查询？".equals(source)) {
            return "Как посмотреть вознаграждение за рекомендации в программе партнёров Changan Qiyuan?";
        }
        if ("启源 APP 里的源宝可以跨账号转移吗？".equals(source)) {
            return "Можно ли перенести бонусы между аккаунтами в приложении Changan Qiyuan?";
        }
        if ("启源 APP 内的源宝与账号绑定，无法跨账号转移。若用户持有多个手机号，通过 A 手机号注册账号获得的源宝，不能转入用户的 B 手机号账号中".equals(source)) {
            return "Бонусы привязаны к аккаунту, поэтому перенести их между аккаунтами нельзя. Бонусы аккаунта с номером A нельзя перенести в аккаунт с номером B.";
        }
        if ("账号注销".equals(source)) return "Удаление аккаунта";
        if (source.startsWith("1.注销申请前，请检查确认:")) {
            return "1. Перед удалением убедитесь, что аккаунт защищён и по нему нет нерешённых финансовых вопросов.\n"
                    + "2. После удаления сведения о заказах, аккаунте и личности, а также бонусы, баллы, уровень и купоны будут удалены без возможности восстановления.\n"
                    + "3. Баллы и привилегии связанных брендов, включая Changan Qiyuan, Deepal и другие, также нельзя будет восстановить.\n"
                    + "4. Аккаунт нельзя удалить, пока к нему привязан автомобиль или есть незавершённые заказы.";
        }
        if ("车控更改手机号码操作。".equals(source)) {
            return "Как изменить номер телефона для управления автомобилем?";
        }
        if (source.startsWith("Q1：我需要绑定新手机号")) {
            return "Вопрос 1. Как привязать новый номер, если прежний отключён или на него не приходит код?\n"
                    + "Ответ. Обратитесь в дилерский центр, где приобретён автомобиль. Дилер подаст заявку на ручную отвязку прежнего номера.\n\n"
                    + "Вопрос 2. Какие документы нужны?\n"
                    + "Ответ. Нужны счёт-фактура на автомобиль и фото лицевой стороны удостоверения личности с совпадающими данными. Вместо счёта-фактуры можно предоставить свидетельство о регистрации или документ о регистрации автомобиля с читаемым VIN. При повторной заявке в течение трёх месяцев потребуется письмо дилера с объяснением, печатью и подписью владельца. Обратитесь в дилерский центр.";
        }
        if ("意见箱".equals(source) || "意见反馈".equals(source)
                || "建议箱".equals(source)) return "Обратная связь";
        if ("建议记录".equals(source)) return "История обращений";
        if ("暂无内容哦~".equals(source)) return "Пока обращений нет";
        if ("我要反馈".equals(source)) return "Создать обращение";
        if ("反馈类型".equals(source)) return "Тип обращения";
        if ("功能建议".equals(source)) return "Предложение";
        if ("问题反馈".equals(source)) return "Сообщить о проблеме";
        if ("投诉".equals(source)) return "Жалоба";
        if ("反馈模块".equals(source)) return "Категория";
        if ("交互体验".equals(source)) return "Удобство использования";
        if ("其他".equals(source)) return "Другое";
        if ("价格".equals(source)) return "Цены";
        if ("请描述您的建议和问题（可添加相关问题截图）".equals(source)) {
            return "Опишите предложение или проблему. Можно приложить скриншот";
        }
        if ("上传图片/视频".equals(source)) return "Добавить фото или видео";
        if ("请输入您的真实姓名以便联系".equals(source)) return "Укажите настоящее имя для связи";
        if ("车型".equals(source)) return "Модель автомобиля";
        if ("为更快速的处理您的问题，请选择您的车型".equals(source)) {
            return "Выберите модель автомобиля, чтобы мы быстрее обработали обращение";
        }
        if ("VIN码".equals(source)) return "VIN";
        if ("请填写您的车架号".equals(source)) return "Введите VIN";
        if ("提交".equals(source)) return "Отправить";
        if ("目前氛围灯的律动模式只匹配酷狗音乐，应该多种音乐APP可灯光随音乐律动，".equals(source)) {
            return "Подсветка синхронизируется только с Kugou Music. Добавьте поддержку других музыкальных приложений";
        }
        if ("23 24款为什么不支持远程查看摄像头视频，只能拍照，明明25款支持天眼视频？".equals(source)) {
            return "Почему модели 2023–2024 годов не показывают видео с камер удалённо, хотя модели 2025 года это поддерживают?";
        }
        if ("车机的酷我音乐可以卸载吗？".equals(source)) return "Можно ли удалить Kuwo Music из автомобиля?";
        if ("Q07的悬挂软硬调节没了？".equals(source)) return "В Q07 пропала регулировка жёсткости подвески?";
        if ("增加动态续航。".equals(source)) return "Добавьте динамический расчёт запаса хода";
        if ("没有午休模式，每次睡着就会断电？".equals(source)) return "Почему нет режима отдыха и питание отключается во время сна?";
        if ("Q07魔毯悬架配置问题相关QA".equals(source)) return "Вопросы о настройке подвески Magic Carpet в Q07";
        if ("QQ音乐为什么没有臻品音质和音效？老款车主都有。".equals(source)) {
            return "Почему в QQ Music нет премиального качества и звуковых эффектов, доступных в старых моделях?";
        }
        if ("怎么查看我的车辆权益？".equals(source)) return "Как посмотреть доступные привилегии автомобиля?";
        if ("预约充电时间到了，没有充电怎么办？".equals(source)) return "Что делать, если запланированная зарядка не началась?";
        if ("关于安装充电桩。".equals(source)) return "Установка зарядной станции";
        if ("订车订金能退吗？".equals(source)) return "Можно ли вернуть задаток за автомобиль?";
        if ("怎么取消订车订单？".equals(source)) return "Как отменить заказ автомобиля?";
        if ("订车后多久交车?".equals(source)) return "Когда доставят автомобиль после заказа?";
        if ("更改线上订单操作方法。".equals(source)) return "Как изменить онлайн-заказ?";
        if ("怎么将APP和车辆绑定？（如何进行车主认证）".equals(source)) {
            return "Как привязать приложение к автомобилю и подтвердить владельца?";
        }
        if ("车机流量2G太少，不够用".equals(source)) return "2 ГБ мобильного трафика в автомобиле недостаточно";
        if ("Q07请设计一种无感退出或者是按键退出，向上抬两次挂挡杆儿推出都会大大提升舒适性".equals(source)) {
            return "Добавьте в Q07 удобный выход кнопкой или двойным движением селектора передач";
        }
        if ("希望车机互联增加carplay。".equals(source)) return "Добавьте поддержку CarPlay";
        if ("哨兵模式能否不要带双闪？".equals(source)) return "Можно ли отключить аварийную сигнализацию в режиме охраны?";
        if ("1.希望哨兵模式的录制视频可以直接上传到手机端可以看见；2.可以增加车外喊话环节吗？".equals(source)) {
            return "Можно ли отправлять записи режима охраны на телефон и добавить внешнюю голосовую связь?";
        }
        if ("能不能增加百公里的燃料消耗数据显示？".equals(source)) return "Можно ли добавить расход топлива на 100 км?";
        if ("OTA升级过程中可以取消吗？".equals(source)) return "Можно ли отменить обновление OTA?";
        if ("OTA升级失败怎么办？".equals(source)) return "Что делать, если обновление OTA завершилось ошибкой?";
        if ("OTA升级过程中遇到了车机黑屏的异常，怎么解决？".equals(source)) {
            return "Что делать, если во время обновления OTA погас экран автомобиля?";
        }
        if ("OTA升级后车机显示屏不显示胎压？".equals(source)) return "После обновления OTA не отображается давление в шинах";
        if ("OTA升级过程中，我遇到一些问题怎么办？".equals(source)) return "Что делать при проблемах во время обновления OTA?";
        if ("建议每次打开app的时候可以自动刷新车辆状态信息。".equals(source)) {
            return "Автоматически обновляйте состояние автомобиля при каждом запуске приложения";
        }
        if ("APP上有油量显示的百分比 每次行程的公里数及油量使用电量的显示。".equals(source)) {
            return "Добавьте уровень топлива, пробег поездки, расход топлива и энергии";
        }
        if ("希望车辆3D模型能匹配上车辆颜色".equals(source)) return "Цвет 3D-модели должен соответствовать автомобилю";
        if ("什么时候鸿蒙系统可以无感解锁呢？".equals(source)) return "Когда появится бесконтактная разблокировка в HarmonyOS?";
        if ("希望哨兵模式能在手机APP上查看。".equals(source)) return "Добавьте просмотр режима охраны в приложении";
        if ("哨兵模式手机上不能查看警报区间的视频片段。".equals(source)) {
            return "На телефоне невозможно посмотреть видео тревоги режима охраны";
        }
        if ("使用聊天功能需注意哪些事项？".equals(source)) return "Что нужно учитывать при использовании чата?";
        if ("聊天".equals(source)) return "Чаты";
        if ("点击查看".equals(source) || "点击进入".equals(source)) return "Открыть";
        if ("购车订单".equals(source)) return "Заказы автомобилей";
        if ("车主商城订单".equals(source)) return "Заказы в магазине";
        if ("积分商城订单".equals(source)) return "Заказы за баллы";
        if ("活动订单".equals(source)) return "Заказы на мероприятия";
        if ("抖音订单".equals(source)) return "Заказы Douyin";
        if ("金融计算".equals(source)) return "Кредитный расчёт";
        if ("我要估价".equals(source)) return "Оценить авто";
        if ("我要卖车".equals(source)) return "Продать авто";
        if ("我要置换".equals(source)) return "Обменять авто";
        if ("用车".equals(source)) return "Обслуживание";
        if ("Pull Down To Refresh".equals(source)) return "Потяните для обновления";
        if ("Pull Up To Load More".equals(source)) return "Потяните для загрузки";
        if (source.startsWith("Last Update ")) return source.replace("Last Update ", "Обновлено ");
        if ("基本信息".equals(source)) return "Личные данные";
        if ("保存".equals(source)) return "Сохранить";
        if ("感情状况".equals(source)) return "Семейное положение";
        if ("已婚".equals(source)) return "В браке";
        if ("未婚".equals(source)) return "Не в браке";
        if ("单身".equals(source)) return "Без пары";
        if ("注册时间".equals(source)) return "Дата регистрации";
        if ("联系方式".equals(source)) return "Телефон";
        if ("邮箱地址".equals(source)) return "Email";
        if ("收货地址".equals(source)) return "Адрес доставки";
        if ("常住地".equals(source)) return "Место проживания";
        if ("家乡".equals(source)) return "Родной город";
        if ("兴趣爱好".equals(source)) return "Увлечения";
        if ("户外探险".equals(source)) return "Активный отдых";
        if ("请选择".equals(source)) return "Выберите";
        if ("未填写".equals(source)) return "Не заполнено";
        if ("取消".equals(source)) return "Отмена";
        if ("确定".equals(source)) return "Готово";
        if ("温馨提示".equals(source)) return "Совет";
        if ("知道".equals(source)) return "Понятно";
        if ("知道了".equals(source) || "了解".equals(source) || "了解了".equals(source)) return "Понятно";
        if ("当前无可使用的紧急钥匙，请下载蓝牙钥匙后再试。".equals(source)) return "Сейчас нет доступного аварийного ключа. Загрузите Bluetooth-ключ и повторите попытку";
        if ("搜索更多精彩活动".equals(source)) return "Поиск интересных событий";
        if ("重庆市".equals(source)) return "Чунцин";
        if ("切换".equals(source)) return "Сменить";
        if ("该地区暂无活动，去其他地区逛逛吧~".equals(source)) return "В этом районе пока нет событий. Посмотрите другие районы~";
        if ("保存成功".equals(source) || "修改成功".equals(source)) return "Изменения сохранены";
        if ("保存失败".equals(source) || "修改失败".equals(source)) return "Не удалось сохранить";
        if ("操作成功".equals(source)) return "Готово";
        if ("操作失败".equals(source)) return "Операция не выполнена";
        if ("网络异常".equals(source) || "网络错误".equals(source)) return "Ошибка сети";
        if ("请求失败".equals(source)) return "Ошибка запроса";
        if ("请稍后重试".equals(source)) return "Повторите позже";
        if ("选择地址".equals(source) || "请选择地址".equals(source)) return "Выберите адрес";
        if ("昵称".equals(source) || "Никнейм".equals(source)) return "Ник";
        if ("最长8个汉字".equals(source)) return "Не более 8 символов";
        if ("个性签名".equals(source) || "Персонализированная подпись".equals(source)) return "Подпись";
        if ("最长16个汉字".equals(source) || "Максимум 16 китайских иероглифов.".equals(source)) {
            return "Не более 16 символов";
        }
        if ("性别".equals(source) || "пол".equals(source)) return "Пол";
        if ("女".equals(source) || "女性".equals(source) || "женский".equals(source)) return "Женский";
        if ("男".equals(source) || "男性".equals(source) || "мужской".equals(source)) return "Мужской";
        if ("生日".equals(source)) return "День рождения";
        if ("星座".equals(source) || "созвездие".equals(source)) return "Знак зодиака";
        if ("Женатый".equals(source) || "Женат".equals(source)) return "В браке";
        if ("неженатый".equals(source) || "Неженатый".equals(source)) return "Не в браке";
        if ("одинокий".equals(source) || "Одинокий".equals(source)) return "Без пары";
        if ("сохранять".equals(source)) return "Сохранить";
        if ("статус отношений".equals(source)) return "Семейное положение";
        if ("обычное место жительства".equals(source)) return "Место проживания";
        if ("родной город".equals(source)) return "Родной город";
        if ("хобби".equals(source)) return "Увлечения";
        if ("приключение на свежем воздухе".equals(source)) return "Активный отдых";
        if ("мне нравится видеть".equals(source)) return "Что нравится";
        if ("обзор автомобиля".equals(source)) return "Обзоры автомобилей";
        if ("цифровые технологии".equals(source)) return "Цифровые технологии";
        if ("Конечно".equals(source)) return "Готово";
        if ("点击注册/登录，即表示已阅读并同意《用户隐私条款》、《长安启源注册会员服务条款》".equals(source)) {
            return "Нажимая «Войти», вы принимаете Политику конфиденциальности и Условия сервиса CHANGAN QIYUAN.";
        }
        return null;
    }

    private static XC_MethodHook canvasTextHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
                String source = param.args[0].toString().trim();
                if (!containsChinese(source)) return;
                String translated = contextualOverride(source);
                if (translated == null) translated = cachedTranslation(source);
                if (translated != null) {
                    param.args[0] = translated;
                } else {
                    queueDrawTranslation(source);
                }
            }
        };
    }

    private static void queueDrawTranslation(String source) {
        if (!DRAW_TRANSLATING.add(source)) return;
        TRANSLATION_EXECUTOR.execute(() -> {
            try {
                String translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    cacheTranslation(source, translated);
                    View root = CURRENT_ROOT.get();
                    if (root != null) root.postInvalidate();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Canvas translation failed for " + abbreviate(source), t);
            } finally {
                DRAW_TRANSLATING.remove(source);
            }
        });
    }

    /**
     * The address picker draws labels directly on a canvas, so they never pass
     * through TextView.setText(). Translate the strings returned by WheelView's
     * own renderer and invalidate it when an asynchronous result arrives.
     */
    private static void installWheelViewHook(ClassLoader classLoader) {
        try {
            Class<?> wheelViewClass = XposedHelpers.findClassIfExists(
                    "com.contrarywind.view.WheelView", classLoader);
            if (wheelViewClass == null) {
                Log.w(TAG, "WheelView class not found; address wheel translation disabled");
                return;
            }
            installWheelViewHook(wheelViewClass, null);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to find WheelView class", t);
        }
    }

    private static void installWheelViewHook(Class<?> wheelViewClass, View wheelView) {
        if (!HOOKED_WHEEL_CLASSES.add(wheelViewClass)) {
            if (wheelView != null) wheelView.postInvalidate();
            return;
        }
        try {
            XposedBridge.hookAllMethods(wheelViewClass, "getContentText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof String)) return;
                    String source = ((String) value).trim();
                    if (!containsChinese(source)) return;

                    String translated = contextualOverride(source);
                    if (translated == null) translated = cachedTranslation(source);
                    if (translated != null) {
                        param.setResult(translated);
                    } else if (param.thisObject instanceof View) {
                        queueWheelTranslation((View) param.thisObject, source);
                    }
                }
            });
            if (wheelView != null) wheelView.postInvalidate();
            Log.i(TAG, "WheelView text hook installed");
        } catch (Throwable t) {
            HOOKED_WHEEL_CLASSES.remove(wheelViewClass);
            Log.w(TAG, "Unable to install WheelView text hook", t);
        }
    }

    private static void queueWheelTranslation(View wheelView, String source) {
        if (!WHEEL_TRANSLATING.add(source)) return;
        WeakReference<View> target = new WeakReference<>(wheelView);
        TRANSLATION_EXECUTOR.execute(() -> {
            try {
                String translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    cacheTranslation(source, translated);
                    View view = target.get();
                    if (view != null) view.post(view::invalidate);
                }
            } catch (Throwable t) {
                Log.w(TAG, "WheelView translation failed for " + abbreviate(source), t);
            } finally {
                WHEEL_TRANSLATING.remove(source);
            }
        });
    }

    /** Translates ordinary HTML text nodes in the app's WebViews. */
    private static void scanWebView(WebView webView) {
        String script = "(function(){var a=[],s={},w=document.createTreeWalker(" +
                "document.body||document.documentElement,NodeFilter.SHOW_TEXT);var n,t,p,e,i;" +
                "while((n=w.nextNode())&&a.length<200){p=n.parentElement;" +
                "if(!p||/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT)$/.test(p.tagName))continue;" +
                "t=(n.nodeValue||'').trim();if(t&&/[\\u3400-\\u9fff\\uf900-\\ufaff]/.test(t)&&!s[t])" +
                "{s[t]=1;a.push(t);}}e=document.querySelectorAll('input[placeholder],textarea[placeholder]');" +
                "for(i=0;i<e.length&&a.length<200;i++){t=(e[i].getAttribute('placeholder')||'').trim();" +
                "if(t&&/[\\u3400-\\u9fff\\uf900-\\ufaff]/.test(t)&&!s[t]){s[t]=1;a.push(t);}}return a;})()";
        try {
            webView.evaluateJavascript(script, value -> {
                if (value == null || "null".equals(value)) return;
                try {
                    JSONArray texts = new JSONArray(value);
                    for (int i = 0; i < texts.length(); i++) {
                        String source = texts.optString(i, "").trim();
                        if (!source.isEmpty()) queueWebTranslation(webView, source);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to parse WebView text list", t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to scan WebView", t);
        }
    }

    private static void queueWebTranslation(WebView webView, String source) {
        String override = contextualOverride(source);
        if (override != null) {
            applyWebTranslation(webView, source, override);
            return;
        }
        String cached = cachedTranslation(source);
        if (cached != null) {
            applyWebTranslation(webView, source, cached);
            return;
        }
        if (!WEB_TRANSLATING.add(source)) return;
        TRANSLATION_EXECUTOR.execute(() -> {
            try {
                String translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    cacheTranslation(source, translated);
                    applyWebTranslation(webView, source, translated);
                }
            } catch (Throwable t) {
                Log.w(TAG, "WebView translation failed for " + abbreviate(source), t);
            } finally {
                WEB_TRANSLATING.remove(source);
            }
        });
    }

    private static void applyWebTranslation(WebView webView, String source, String translated) {
        String script = "(function(){var s=" + JSONObject.quote(source) + ",t=" +
                JSONObject.quote(translated) + ",w=document.createTreeWalker(" +
                "document.body||document.documentElement,NodeFilter.SHOW_TEXT),n,c=0,e,i,p;" +
                "while(n=w.nextNode()){if((n.nodeValue||'').trim()===s){" +
                "n.nodeValue=n.nodeValue.replace(s,t);c++;}}" +
                "e=document.querySelectorAll('input[placeholder],textarea[placeholder]');" +
                "for(i=0;i<e.length;i++){p=(e[i].getAttribute('placeholder')||'').trim();" +
                "if(p===s){e[i].setAttribute('placeholder',t);c++;}}return c;})()";
        webView.post(() -> {
            try {
                webView.evaluateJavascript(script, null);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to apply WebView translation", t);
            }
        });
    }

    private static void applyIfCurrent(TextView view, String source, String translated, boolean hint) {
        view.post(() -> {
            CharSequence currentValue = hint ? view.getHint() : view.getText();
            if (currentValue == null || !source.equals(currentValue.toString().trim())) return;
            try {
                APPLYING_TRANSLATION.set(Boolean.TRUE);
                if (hint) view.setHint(translated);
                else view.setText(translated);
            } finally {
                APPLYING_TRANSLATION.remove();
            }
        });
    }

    private static String cachedTranslation(String source) {
        String translated = CACHE.get(source);
        if (translated != null) return translated;

        SharedPreferences persistent = PERSISTENT_CACHE;
        if (persistent == null) return null;
        try {
            translated = persistent.getString(source, null);
            if (translated != null && !translated.isEmpty()) CACHE.put(source, translated);
            return translated;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read persistent translation cache", t);
            return null;
        }
    }

    private static void cacheTranslation(String source, String translated) {
        if (CACHE.size() >= MAX_CACHE_SIZE) CACHE.clear();
        CACHE.put(source, translated);

        SharedPreferences persistent = PERSISTENT_CACHE;
        if (persistent == null) return;
        try {
            persistent.edit().putString(source, translated).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to write persistent translation cache", t);
        }
    }

    private static String requestGoogleTranslation(String source) throws Exception {
        boolean containsBrand = source.contains("长安启源");
        String textToTranslate = containsBrand
                ? source.replace("长安启源", "XQYBRANDX")
                : source;
        String query = URLEncoder.encode(textToTranslate, StandardCharsets.UTF_8.name());
        URL url = new URL("https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=zh-CN&tl=ru&dt=t&q=" + query);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) QiyuanRus/2.0");

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + status);
            }

            StringBuilder payload = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) payload.append(line);
            }

            JSONArray segments = new JSONArray(payload.toString()).getJSONArray(0);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray segment = segments.optJSONArray(i);
                if (segment != null && !segment.isNull(0)) result.append(segment.getString(0));
            }
            String translated = result.toString().trim();
            if (containsBrand) {
                translated = translated.replaceAll("(?i)XQYBRANDX", "CHANGAN QIYUAN");
            }
            Log.i(TAG, abbreviate(source) + " -> " + abbreviate(translated));
            return translated.isEmpty() ? null : translated;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff')) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String value) {
        if (value == null) return "<null>";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "...";
    }

    private static final class PendingText {
        final WeakReference<TextView> view;
        final String source;
        final boolean hint;

        PendingText(TextView view, String source, boolean hint) {
            this.view = new WeakReference<>(view);
            this.source = source;
            this.hint = hint;
        }
    }
}
