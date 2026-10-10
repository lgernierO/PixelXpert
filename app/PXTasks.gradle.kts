val taskGroup = "pixelxpert"

tasks.register("buildCanary") {
	group = taskGroup
	description = "Builds a Canary APK with count-based versioning"

	// finalizer 即使前置任务失败也会执行；打包必须走成功依赖链。
	dependsOn("incrementCanaryVersion", "createZip")
}

tasks.register("buildStable") {
	group = taskGroup
	description = "Builds a Stable APK with Git Tag versioning"

	dependsOn("incrementStableVersion", "createZip")
}

tasks.register<IncrementVersionTask>("incrementCanaryVersion") {
	group = taskGroup
	description = "Increments the canary version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))

	stable.set(false)

	filesToUpdate.from(
		rootProject.file("MagiskModBase/module.prop"),
		rootProject.file("latestCanary.json"),
		rootProject.file("MagiskModuleUpdate_Xposed.json"),
		rootProject.file("MagiskModuleUpdate_Full.json")
	)
	targetVersionName.set(getVersionName())
}

tasks.register<IncrementVersionTask>("incrementStableVersion") {
	group = taskGroup
	description = "Increments the stable version and saves it to the related files"

	versionFile.set(rootProject.file("version.properties"))
	stable.set(true)
	filesToUpdate.from(
		rootProject.file("MagiskModBase/module.prop"),
		rootProject.file("latestStable.json"),
		rootProject.file("MagiskModuleUpdate.json"),
		rootProject.file("MagiskModuleUpdate_Full.json"),
		rootProject.file("MagiskModuleUpdate_Xposed.json"),
	)
	targetVersionName.set(getVersionName())
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
	mustRunAfter("incrementCanaryVersion", "incrementStableVersion")
}

tasks.register<Zip>("createZip") {
	group = taskGroup
	description = "Creates the final Magisk module file"
	dependsOn("assembleRelease")
	doFirst {
		val required = listOf(
			rootProject.file("MagiskModBase/zygisk/arm64-v8a.so"),
			rootProject.file("MagiskModBase/zygisk/armeabi-v7a.so"),
			rootProject.file("MagiskModBase/sqlite3"),
			file("build/outputs/apk/release/PixelXpert.apk")
		)
		val missing = required.filter { !it.isFile || it.length() == 0L }
		check(missing.isEmpty()) { "缺少完整模块产物，拒绝生成 ZIP：${missing.joinToString()}" }
	}


	mustRunAfter("incrementCanaryVersion")
	mustRunAfter("incrementStableVersion")
	mustRunAfter("assembleRelease")

	from(file("../MagiskModBase"))
	from(file("build/outputs/apk/release/PixelXpert.apk")){into("system/priv-app/PixelXpert")}

	destinationDirectory.set(file("../output"))
	archiveFileName.set("PixelXpert.zip")
}