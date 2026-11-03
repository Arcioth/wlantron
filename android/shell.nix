# nix-shell --run "gradle assembleDebug"
# SDK bits are unfree. The license flag is the Android SDK license, accepted here
# so the build does not stop to ask.
{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:
let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "20.0";
    buildToolsVersions = [ "35.0.0" ];
    platformVersions = [ "35" ];
    includeEmulator = false;
    includeSources = false;
    includeSystemImages = false;
    includeNDK = false;
    includeCmake = false;
  };
  androidSdk = androidComposition.androidsdk;
  jdk = pkgs.jdk;
in
pkgs.mkShell {
  packages = [
    androidSdk
    jdk
    pkgs.gradle
  ];
  JAVA_HOME = jdk.home;
  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
  shellHook = ''
    export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$(echo "$ANDROID_HOME/build-tools/"*"/aapt2")"
    cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
EOF
  '';
}
