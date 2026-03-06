@echo off
REM Accept Android SDK licenses for this workspace.
REM Source code for the project is based on AOSP; this step only covers SDK tools/licenses.
REM If your SDK is installed elsewhere, update ANDROID_HOME and ANDROID_SDK_ROOT.
set ANDROID_HOME=F:\APP
set ANDROID_SDK_ROOT=F:\APP
echo y | "F:\APP\cmdline-tools\bin\sdkmanager.bat" --licenses
