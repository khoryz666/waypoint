{
  description = "Waypoint Android dev environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # Only what Gradle needs to build: one platform, one build-tools, adb.
      # Legacy SDK tools, CMake and the Google APIs add-on are left out.
      sdkArgs = {
        cmdLineToolsVersion = "13.0";
        platformToolsVersion = "37.0.1";
        buildToolsVersions = [ "35.0.0" ];
        platformVersions = [ "35" ];
        toolsVersion = null;
        includeCmake = false;
        includeNDK = false;
        includeSources = false;
      };

      # The emulator and its system image are large, so they live in a separate shell.
      emulatorArgs = sdkArgs // {
        includeEmulator = true;
        emulatorVersion = "37.2.4";
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      };

      mkShell =
        args:
        let
          sdk = (pkgs.androidenv.composeAndroidPackages args).androidsdk;
          sdkRoot = "${sdk}/libexec/android-sdk";
        in
        pkgs.mkShell {
          packages = [
            pkgs.jdk17_headless
            sdk
          ];
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          # The aapt2 Gradle downloads from Maven is dynamically linked and won't run on NixOS.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/35.0.0/aapt2";
          shellHook = ''
            export PATH="${sdkRoot}/platform-tools:${sdkRoot}/emulator:$PATH"
          '';
        };
    in
    {
      devShells.${system} = {
        default = mkShell sdkArgs;
        emulator = mkShell emulatorArgs;
      };
    };
}
