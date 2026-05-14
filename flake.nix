{
  description = "Agrapha — meeting recorder and transcriber";

  inputs = {
    nixpkgs.url     = "github:NixOS/nixpkgs/nixos-unstable";
    rust-overlay    = {
      url    = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, rust-overlay, flake-utils }:
    flake-utils.lib.eachSystem [
      "x86_64-linux"
      "aarch64-darwin"
      "x86_64-darwin"
    ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree   = true;   # Temurin JDK, CUDA toolkit
            cudaSupport   = pkgs.stdenv.isLinux;
          };
          overlays = [ rust-overlay.overlays.default ];
        };

        # ── Toolchain pins ────────────────────────────────────────────────────
        # Matches CI: actions/setup-java distribution=temurin java-version=17
        jdk = pkgs.temurin-bin-17;

        # Pin stable Rust; rust-src enables rust-analyzer go-to-definition.
        rustToolchain = pkgs.rust-bin.stable.latest.default.override {
          extensions = [ "rust-src" "rust-analyzer" ];
        };

        # ── Shared packages (all platforms) ───────────────────────────────────
        commonPackages = with pkgs; [
          jdk
          rustToolchain
          cmake          # whisper-jni CoreML (macOS) and CUDA (Linux) builds
          ninja          # faster CMake backend
          git            # Makefile clones whisper-jni at build time
          uv             # Python env manager: AMI download, diarize_session.py
          python3
        ];

        # ── Linux-only packages ───────────────────────────────────────────────
        # Covers: agrapha-native Cargo build, CUDA whisper build, desktop tests.
        linuxPackages = with pkgs; [
          pkg-config
          pipewire.dev                  # PipeWire + SPA headers for agrapha-native Rust crate
          libx11
          libx11.dev
          xvfb-run                      # headless display for desktopTest in CI
          # CUDA toolkit: nvcc compiler + headers + stub libs.
          # The NVIDIA kernel driver (libcuda.so.1) is provided by the host OS —
          # Nix supplies the build toolchain only, not the runtime driver.
          cudaPackages.cudatoolkit
        ];

        # ── macOS-only packages ───────────────────────────────────────────────
        # Swift + coremlc ship with Xcode, which must be installed separately.
        # No extra Nix packages needed beyond commonPackages.
        darwinPackages = [ ];

      in {
        devShells.default = pkgs.mkShell {
          packages = commonPackages
            ++ pkgs.lib.optionals pkgs.stdenv.isLinux  linuxPackages
            ++ pkgs.lib.optionals pkgs.stdenv.isDarwin darwinPackages;

          # Let Cargo build scripts find PipeWire and SPA via pkg-config.
          PKG_CONFIG_PATH = pkgs.lib.optionalString pkgs.stdenv.isLinux
            "${pkgs.pipewire.dev}/lib/pkgconfig";

          shellHook = ''
            export JAVA_HOME="${jdk}"

            ${pkgs.lib.optionalString pkgs.stdenv.isLinux ''
              # Put nvcc on PATH and expose CUDA_PATH for CMake FindCUDA.
              export CUDA_PATH="${pkgs.cudaPackages.cudatoolkit}"
              export PATH="$CUDA_PATH/bin:$PATH"

              # PipeWire runtime .so — needed when running the app from the shell.
              export LD_LIBRARY_PATH="${pkgs.pipewire}/lib''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            ''}

            ${pkgs.lib.optionalString pkgs.stdenv.isDarwin ''
              if ! command -v swift &>/dev/null; then
                echo "warning: Swift not found — install Xcode to build the FluidAudio bridge"
              fi
            ''}

            echo "Agrapha dev shell ready"
            echo "  java    $(java -version 2>&1 | head -1)"
            echo "  rustc   $(rustc --version)"
            echo "  cmake   $(cmake --version | head -1)"
            ${pkgs.lib.optionalString pkgs.stdenv.isLinux
              ''echo "  nvcc    $(nvcc --version 2>/dev/null | grep 'release' || echo 'not found')"''}
          '';
        };
      }
    );
}
