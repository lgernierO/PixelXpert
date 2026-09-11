#!/system/bin/sh
# KernelSU "action" button: launch the PixelXpert settings app
PKG=sh.siava.pixelxpert
ACTIVITY=sh.siava.pixelxpert/.ui.activities.FakeSplashActivity

if pm path $PKG > /dev/null 2>&1; then
	echo "- Launching PixelXpert..."
	am start -n $ACTIVITY
else
	echo "! PixelXpert app is not installed"
fi
