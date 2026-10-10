package sh.siava.pixelxpert.xposed.utils;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.WeakHashMap;

import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

/** 将文本放进真实 Smartspace AndroidView，跟随其布局、解锁可见性及 AOD 位移。 */
public final class LockscreenCustomText {
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Runnable refreshColor;
	private final ArrayList<WeakReference<TextHost>> hosts = new ArrayList<>();
	private final WeakHashMap<Object, WeakReference<Object>> factories = new WeakHashMap<>();
	private final WeakHashMap<Object, WeakReference<Object>> callbacks = new WeakHashMap<>();
	private Class<?> rowClass, columnClass;
	private CharSequence text = "";
	private int color = Color.WHITE;
	private int revision;
	private boolean warned;

	public LockscreenCustomText(Runnable refreshColor) {
		this.refreshColor = refreshColor;
	}

	public void install() {
		String prefix = "com.android.systemui.keyguard.ui.composable.elements.SmartspaceElementProvider";
		rowClass = ReflectedClass.ofIfPossible(prefix + "$DWARowElement").getClazz();
		columnClass = ReflectedClass.ofIfPossible(prefix + "$DWAColumnElement").getClazz();
		ReflectedClass companion = ReflectedClass.ofIfPossible(prefix + "$Companion");
		if (rowClass == null || columnClass == null || companion.getClazz() == null) {
			warn("当前 SystemUI 没有已验证的 Smartspace 文本入口", null);
			return;
		}
		companion.before("LookaheadAndroidView").run(param -> {
			if (param.args.length != 7 || param.args[0] == null) return;
			Object factory = param.args[0];
			if (!isOurProxy(factory) && !capturesDateWeatherElement(factory)) return;
			Class<?> function = ((Method) param.method).getParameterTypes()[0];
			param.args[0] = wrapFunction(factory, function, true);
			// 当前 APK 第 3/4 个参数分别是 release/update。只解包业务回调，
			// Lookahead 自己的监听器仍绑定外层，因此增加文本会触发正常重新测量。
			for (int index : new int[]{2, 3}) {
				if (param.args[index] != null) {
					param.args[index] = wrapFunction(param.args[index], function, false);
				}
			}
		});
	}

	private boolean capturesDateWeatherElement(Object factory) {
		for (Class<?> type = factory.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
				try {
					field.setAccessible(true);
					Object value = field.get(factory);
					if (rowClass.isInstance(value) || columnClass.isInstance(value)) return true;
				} catch (ReflectiveOperationException ignored) {}
			}
		}
		return false;
	}

	private boolean isOurProxy(Object function) {
		return Proxy.isProxyClass(function.getClass())
				&& Proxy.getInvocationHandler(function) instanceof FunctionWrapper;
	}

	private Object wrapFunction(Object original, Class<?> functionType, boolean factory) {
		if (isOurProxy(original)) return original;
		WeakHashMap<Object, WeakReference<Object>> cache = factory ? factories : callbacks;
		WeakReference<Object> entry = cache.get(original);
		Object cached = entry == null ? null : entry.get();
		if (cached != null) return cached;
		Object proxy = Proxy.newProxyInstance(functionType.getClassLoader(), new Class<?>[]{functionType},
				new FunctionWrapper(original, factory));
		// value 也必须弱引用，否则代理持有 original 会把弱键重新强引用住。
		cache.put(original, new WeakReference<>(proxy));
		return proxy;
	}

	private final class FunctionWrapper implements InvocationHandler {
		private final Object original;
		private final boolean factory;

		private FunctionWrapper(Object original, boolean factory) {
			this.original = original;
			this.factory = factory;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "PixelXpertLockscreenFunction";
					default -> method.invoke(original, args);
				};
			}
			Object[] forwarded = args;
			if (!factory && args != null && args.length == 1 && args[0] instanceof TextHost host) {
				forwarded = new Object[]{host.content};
			}
			Object result;
			try {
				result = method.invoke(original, forwarded);
			} catch (InvocationTargetException error) {
				throw error.getCause();
			}
			if (factory && result instanceof View view) return createHost(view);
			if (!factory && args != null && args.length == 1 && args[0] instanceof TextHost) {
				refreshColor.run();
			}
			return result;
		}
	}

	private View createHost(View content) {
		if (content.getParent() instanceof TextHost host) return host;
		if (content.getParent() != null) {
			warn("Smartspace 已有其他父容器，保留系统视图，未强行移动", null);
			return content;
		}
		TextHost host = new TextHost(content);
		hosts.add(new WeakReference<>(host));
		host.apply();
		refreshColor.run();
		return host;
	}

	public void setText(CharSequence value) {
		runOnMain(() -> {
			text = value == null ? "" : value;
			revision++;
			refreshHosts();
		});
	}

	public void setColor(int value) {
		runOnMain(() -> {
			color = value;
			refreshHosts();
		});
	}

	private void runOnMain(Runnable action) {
		if (Looper.myLooper() == Looper.getMainLooper()) action.run();
		else mainHandler.post(action);
	}

	private void refreshHosts() {
		hosts.removeIf(reference -> reference.get() == null);
		for (WeakReference<TextHost> reference : hosts) {
			TextHost host = reference.get();
			if (host != null) host.apply();
		}
	}

	private void warn(String message, Throwable error) {
		if (warned) return;
		warned = true;
		Logger.log("PixelXpert 锁屏文本：" + message, error);
	}

	private final class TextHost extends LinearLayout {
		private final View content;
		private final TextView label;
		private int appliedRevision = -1;

		private TextHost(View content) {
			super(content.getContext());
			this.content = content;
			setOrientation(VERTICAL);
			setGravity(Gravity.CENTER_HORIZONTAL);
			ViewGroup.LayoutParams original = content.getLayoutParams();
			// 外层必须测量新增文本，不能沿用系统原视图的固定高度。
			setLayoutParams(new ViewGroup.LayoutParams(original == null ? WRAP_CONTENT : original.width, WRAP_CONTENT));
			addView(content, new LinearLayout.LayoutParams(original == null ? WRAP_CONTENT : original.width,
					original == null || original.height == MATCH_PARENT ? WRAP_CONTENT : original.height));
			label = new TextView(content.getContext());
			label.setMaxLines(2);
			label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
			label.setLetterSpacing(.03f);
			label.setGravity(Gravity.CENTER_HORIZONTAL);
			addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		}

		private void apply() {
			// 不在每次 Compose update 重写同一文本，避免布局监听形成重组循环。
			if (appliedRevision != revision) {
				label.setText(text);
				label.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
				appliedRevision = revision;
			}
			if (label.getCurrentTextColor() != color) {
				label.setTextColor(color);
				label.setShadowLayer(1, 1, 1, color == Color.BLACK ? Color.TRANSPARENT : Color.BLACK);
			}
		}
	}
}
