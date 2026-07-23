package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;




import android.content.Context;
import android.view.View;

import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class EasyUnlock extends XposedModPack {
	private int expectedPassLen = -1;
	private boolean easyUnlockEnabled = false;

	private int lastPassLen = 0;
	private boolean composeCredentialInputActive = false;
	private static boolean WakeUpToSecurityInput = false;

	public EasyUnlock(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false);
		expectedPassLen = Xprefs.getInt("expectedPassLen", -1);
		WakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass KeyguardAbsKeyInputViewControllerClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardAbsKeyInputViewController");
		ReflectedClass StatusBarKeyguardViewManagerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");

		StatusBarKeyguardViewManagerClass
				.before("onDozingChanged")
				.run(param -> {
					//noinspection ConstantValue
					if(WakeUpToSecurityInput && param.args[0].equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")))//waking up
					{
						try {
							// Android 17 Scene bouncer API.
							callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked");
						} catch (Throwable ignored) {
							// Legacy View bouncer API.
							callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked", true);
						}
					}
				});

		if (KeyguardAbsKeyInputViewControllerClass.getClazz() != null) {
			ReflectedClass LockscreenCredentialClass = ReflectedClass.of("com.android.internal.widget.LockscreenCredential");
		KeyguardAbsKeyInputViewControllerClass
						.after("onUserInput")
				.run(param -> {
					if (!easyUnlockEnabled) return;

					int passwordLen = (int) callMethod(getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText"), "length");

					if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
						new Thread(() -> {
							try { //don't crash systemUI if failed
								int userId;
								try { //14 QPR3 beta 2.1
									userId = (int) callMethod(
											getObjectField(
													getObjectField(param.thisObject, "mKeyguardUpdateMonitor"),
													"mSelectedUserInteractor")
											, "getSelectedUserId");
								}
								catch (Throwable ignored)
								{ //14 QPR3 beta 2 and older
									userId = (int) getObjectField(getObjectField(param.thisObject, "mKeyguardUpdateMonitor"), "sCurrentUser");
								}

								String methodName = param.thisObject.getClass().getName().contains("Password") ? "createPassword" : "createPin";

								Object password = LockscreenCredentialClass.callStaticMethod(methodName, getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText").toString());

								Object verificationResult = callMethod(
										getObjectField(param.thisObject, "mLockPatternUtils"),
										"checkCredential",
										password,
										userId,
										null /* callback */);

								boolean accepted;

								try{ //16qpr3
									accepted = (boolean) callMethod(verificationResult, "isMatched");
								}
								catch (Throwable ignored) //older
								{
									accepted = (boolean) verificationResult;
								}

								if (accepted) {
									View mView = (View) getObjectField(param.thisObject, "mView");
									int finalUserId = userId;
									mView.post(() -> {
										try { //13 QPR3
											callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, getObjectField(param.thisObject, "mSecurityMode"));
										} catch (Throwable ignored) {}
									});
								}
							} catch (Throwable ignored){}
						}).start();
					}
					lastPassLen = passwordLen;
				});

		KeyguardAbsKeyInputViewControllerClass
				.after("onPasswordChecked")
				.run(param -> {
					if (!easyUnlockEnabled) return;

					boolean successful = (boolean) param.args[1];

					if (successful) {
						expectedPassLen = lastPassLen;
						Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
					}
				});
		}

		// Android 17 Compose bouncer. Its ViewModel owns authentication and remains stable
		// across the Scene implementation, so only observe the input/result boundary.
		ReflectedClass PinBouncerViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel");
		ReflectedClass PasswordBouncerViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel");
		ReflectedClass AuthenticationInteractorClass = ReflectedClass.ofIfPossible("com.android.systemui.authentication.domain.interactor.AuthenticationInteractor");
		if (PinBouncerViewModelClass.getClazz() != null) {
			ReflectedClass AuthMethodBouncerViewModelClass = ReflectedClass.of("com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel");
			PinBouncerViewModelClass.after("onPinButtonClicked").run(param -> {
				if (!easyUnlockEnabled) return;
				try {
					int previousPassLen = lastPassLen;
					lastPassLen = (int) callMethod(param.thisObject, "getEnteredPinLength");
					composeCredentialInputActive = true;
					if (lastPassLen == expectedPassLen && lastPassLen > previousPassLen) {
						// Queue a normal authentication request. The built-in request made by
						// onPinButtonClicked is auto-confirm-only and may be skipped.
						AuthMethodBouncerViewModelClass.callStaticMethod(
								"tryAuthenticate$default", param.thisObject, null, false, 1);
					}
				} catch (Throwable ignored) {}
			});
			PinBouncerViewModelClass.after("clearInput").run(param -> {
				lastPassLen = 0;
				composeCredentialInputActive = false;
			});
		}
		if (PasswordBouncerViewModelClass.getClazz() != null) {
			PasswordBouncerViewModelClass.after("getInput").run(param -> {
				if (easyUnlockEnabled && param.getResult() instanceof List) {
					lastPassLen = ((List<?>) param.getResult()).size();
					composeCredentialInputActive = lastPassLen > 0;
				}
			});
			PasswordBouncerViewModelClass.after("clearInput").run(param -> {
				lastPassLen = 0;
				composeCredentialInputActive = false;
			});
		}
		if (AuthenticationInteractorClass.getClazz() != null) {
			AuthenticationInteractorClass.before("notifyOnAuthResultListeners").run(param -> {
				if (!easyUnlockEnabled || !composeCredentialInputActive || param.args.length == 0) return;
				if (Boolean.TRUE.equals(param.args[0]) && lastPassLen > 0) {
					expectedPassLen = lastPassLen;
					Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
				}
				composeCredentialInputActive = false;
			});
		}

	}
}