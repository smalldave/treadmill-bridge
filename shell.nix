{ pkgs ? import <nixpkgs> {} }:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "33" ];
    buildToolsVersions = [ "30.0.3" "33.0.2" ];
    includeNDK = false;
    includeEmulator = false;
    includeSystemImages = false;
  };
  androidSdk = androidComposition.androidsdk;
in
pkgs.mkShell {
  buildInputs = [
    pkgs.jdk17
    pkgs.gradle
    androidSdk
    pkgs.autoPatchelfHook
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/33.0.2/aapt2";

  shellHook = ''
    echo "Android SDK: $ANDROID_HOME"
    echo "Java: $(java -version 2>&1 | head -1)"
    echo ""
    echo "Build:   gradle assembleDebug"
    echo "Install: adb install app/build/outputs/apk/debug/app-debug.apk"
    echo "Launch:  adb shell am start -n com.treadmill.bridge/.MainActivity"
    echo ""
    echo "IMPORTANT: Stop wolf first: adb shell am force-stop com.ifit.wolf"
  '';
}
