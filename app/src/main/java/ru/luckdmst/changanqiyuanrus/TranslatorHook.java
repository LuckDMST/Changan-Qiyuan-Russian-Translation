package ru.luckdmst.changanqiyuanrus;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CopyOnWriteArrayList<PendingText>> WAITING =
            new ConcurrentHashMap<>();
    private static final Set<String> WEB_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<String> WHEEL_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<String> DRAW_TRANSLATING = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> HOOKED_WHEEL_CLASSES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> APPLYING_TRANSLATION = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPLYING_IMAGE_REPLACEMENT = new ThreadLocal<>();
    private static volatile WeakReference<View> CURRENT_ROOT = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        Log.i(TAG, "Installing dynamic native-text translator for " + TARGET_PACKAGE);
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", textHook(false));
            XposedBridge.hookAllMethods(TextView.class, "setHint", textHook(true));
            XposedBridge.hookAllMethods(Activity.class, "onResume", activityScanHook());
            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", activityScanHook());
            XposedBridge.hookAllMethods(Dialog.class, "show", dialogScanHook());
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupScanHook());
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupScanHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable", imageSetterHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageBitmap", imageSetterHook());
            XposedBridge.hookAllMethods(ImageView.class, "setImageResource", imageSetterHook());
            XposedBridge.hookAllMethods(Canvas.class, "drawText", canvasTextHook());
            installWheelViewHook(lpparam.classLoader);
            Log.i(TAG, "Dynamic TextView, Activity and Dialog scan hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "Dynamic translator hook installation failed", t);
        }
    }

    private static XC_MethodHook activityScanHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                scheduleActivityScan(activity, 0);
                scheduleActivityScan(activity, 350);
                scheduleActivityScan(activity, 1500);
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

        String cached = CACHE.get(source);
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
                    if (CACHE.size() >= MAX_CACHE_SIZE) CACHE.clear();
                    CACHE.put(source, translated);
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
        if ("购车".equals(source)) return "Купить";
        if ("去授权".equals(source)) return "Разрешить";
        if ("长安启源".equals(source)) return "CHANGAN QIYUAN";
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
        if ("遥控车型".equals(source) || "远程控车".equals(source)
                || "Модель автомобиля с дистанционным управлением".equals(source)) return "Пульт авто";
        if ("紧急钥匙".equals(source) || "Экстренный ключ".equals(source)) return "SOS-ключ";
        if ("发现".equals(source)) return "Обзор";
        if ("商城".equals(source)) return "Магазин";
        if ("我的".equals(source)) return "Профиль";
        if ("推荐".equals(source)) return "Рекомендуем";
        if ("看车".equals(source)) return "Автомобили";
        if ("粉丝".equals(source)) return "Подписчики";
        if ("关注".equals(source)) return "Подписки";
        if ("发布".equals(source)) return "Публикации";
        if ("喜欢".equals(source)) return "Лайки";
        if ("元宝".equals(source)) return "Юаньбао";
        if ("定级积分".equals(source)) return "Рейтинг";
        if ("挂件".equals(source)) return "Украшения";
        if ("车主店铺".equals(source)) return "Магазин";
        if ("同城活动".equals(source)) return "События";
        if ("我的卡券".equals(source)) return "Купоны";
        if ("我的订单".equals(source)) return "Заказы";
        if ("在线评价".equals(source)) return "Отзывы";
        if ("我的活动".equals(source)) return "Активность";
        if ("我的圈子".equals(source)) return "Сообщества";
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
        if ("相机".equals(source)) return "Камера";
        if ("麦克风".equals(source)) return "Микрофон";
        if ("照片和视频".equals(source)) return "Фото и видео";
        if ("通知".equals(source)) return "Уведомления";
        if ("通讯录".equals(source)) return "Контакты";
        if ("附近设备".equals(source)) return "Устройства рядом";
        if ("账号密码".equals(source) || "账户密码".equals(source)) return "Пароль";
        if ("未设置".equals(source)) return "Не задан";
        if ("我的消息".equals(source)) return "Сообщения";
        if ("官方通知".equals(source)) return "Официальные";
        if ("个人互动".equals(source)) return "Личные уведомления";
        if ("用车消息".equals(source)) return "Автомобиль";
        if ("在线客服".equals(source)) return "Поддержка";
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
                if (translated == null) translated = CACHE.get(source);
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
                    if (CACHE.size() >= MAX_CACHE_SIZE) CACHE.clear();
                    CACHE.put(source, translated);
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
                    if (translated == null) translated = CACHE.get(source);
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
                    if (CACHE.size() >= MAX_CACHE_SIZE) CACHE.clear();
                    CACHE.put(source, translated);
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
                "document.body||document.documentElement,NodeFilter.SHOW_TEXT);var n,t,p;" +
                "while((n=w.nextNode())&&a.length<200){p=n.parentElement;" +
                "if(!p||/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT)$/.test(p.tagName))continue;" +
                "t=(n.nodeValue||'').trim();if(t&&/[\\u3400-\\u9fff\\uf900-\\ufaff]/.test(t)&&!s[t])" +
                "{s[t]=1;a.push(t);}}return a;})()";
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
        String cached = CACHE.get(source);
        if (cached != null) {
            applyWebTranslation(webView, source, cached);
            return;
        }
        if (!WEB_TRANSLATING.add(source)) return;
        TRANSLATION_EXECUTOR.execute(() -> {
            try {
                String translated = requestGoogleTranslation(source);
                if (translated != null && !translated.isEmpty()) {
                    if (CACHE.size() >= MAX_CACHE_SIZE) CACHE.clear();
                    CACHE.put(source, translated);
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
                "document.body||document.documentElement,NodeFilter.SHOW_TEXT),n,c=0;" +
                "while(n=w.nextNode()){if((n.nodeValue||'').trim()===s){" +
                "n.nodeValue=n.nodeValue.replace(s,t);c++;}}return c;})()";
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
