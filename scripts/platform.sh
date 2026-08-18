case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)   LRC_PLATFORM=linux-x86_64;  JDK_OS=linux; JDK_ARCH=x64;     NODE_PLAT=linux-x64 ;;
  Linux-aarch64)  LRC_PLATFORM=linux-arm64;   JDK_OS=linux; JDK_ARCH=aarch64; NODE_PLAT=linux-arm64 ;;
  Darwin-x86_64)  LRC_PLATFORM=darwin-x86_64; JDK_OS=mac;   JDK_ARCH=x64;     NODE_PLAT=darwin-x64 ;;
  Darwin-arm64)   LRC_PLATFORM=darwin-arm64;  JDK_OS=mac;   JDK_ARCH=aarch64; NODE_PLAT=darwin-arm64 ;;
  *) echo "unsupported platform: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

LRC_TOOLS="tools/$LRC_PLATFORM"
LRC_VENV=".venv-$LRC_PLATFORM"
