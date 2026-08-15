package sh.siava.pixelxpert.xposed.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.annotations.BaseModPack;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@BaseModPack(targetPackage = Constants.GBOARD_PACKAGE)
public @interface GboardModPack { }
