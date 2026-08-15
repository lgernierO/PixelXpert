package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.callMethod;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.getIntField;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.getObjectField;
import static sh.siava.pixelxpert.xposed.utils.reflection.XposedCompat.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;





import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyGuardPinScrambler extends XposedModPack {
	private static boolean shufflePinEnabled = false;

	public KeyGuardPinScrambler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false);
	}

	final List<Integer> digits = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0);

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass KeyguardPinBasedInputViewClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardPinBasedInputView");

		ReflectionConsumer pinShuffleHook = param -> {
			if (!shufflePinEnabled) return;

			Collections.shuffle(digits);

			Object[] mButtons = (Object[]) getObjectField(param.thisObject, "mButtons");

			for(Object button : mButtons)
			{
				int mDigit = getIntField(button, "mDigit");
				setObjectField(button, "mDigit", digits.get(mDigit));

				callMethod(
						getObjectField(button, "mDigitText"),
						"setText",
						Integer.toString(digits.get(mDigit)));
			}
		};


		KeyguardPinBasedInputViewClass.after("onFinishInflate").run(pinShuffleHook);
		KeyguardPinBasedInputViewClass.after("resetPasswordText").run(pinShuffleHook);

		// Android 17 uses a Compose PIN pad. Keep zero fixed because its callback
		// is specialized, and shuffle 1-9 in the stable DigitButton composable.
		ReflectedClass PinBouncerKtClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.composable.PinBouncerKt");
		Map<Object, int[]> composeMappings = Collections.synchronizedMap(new IdentityHashMap<>());
		ThreadLocal<int[]> activeMapping = new ThreadLocal<>();
		PinBouncerKtClass.before(Pattern.compile("PinPad-.*")).run(param -> {
			if (!shufflePinEnabled || param.args.length == 0) return;
			activeMapping.set(composeMappings.computeIfAbsent(param.args[0], ignored -> createComposeMapping()));
		});
		PinBouncerKtClass.after(Pattern.compile("PinPad-.*")).run(param -> activeMapping.remove());
		PinBouncerKtClass.before(Pattern.compile("DigitButton-.*")).run(param -> {
			if (!shufflePinEnabled || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
			int digit = (int) param.args[0];
			int[] mapping = activeMapping.get();
			if (mapping != null && digit >= 1 && digit <= 9) param.args[0] = mapping[digit];
		});
	}

	private int[] createComposeMapping() {
		ArrayList<Integer> shuffled = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));
		Collections.shuffle(shuffled);
		int[] mapping = new int[10];
		mapping[0] = 0;
		for (int i = 1; i <= 9; i++) mapping[i] = shuffled.get(i - 1);
		return mapping;
	}
}
