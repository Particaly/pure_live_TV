import 'dart:io' show Platform;
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';

class PlatformUtils {
  PlatformUtils._();
  static bool get isDesktop => Platform.isWindows || Platform.isLinux || Platform.isMacOS;
  static bool get isDesktopNotMac => (Platform.isWindows || Platform.isLinux) && !Platform.isMacOS;
  static bool get isMobile => Platform.isAndroid || Platform.isIOS;
  static bool isMobileWidth(BuildContext context) => MediaQuery.of(context).size.width < 760;
  static bool get isWindows => Platform.isWindows;
  static bool get isMacOS => Platform.isMacOS;
  static bool get isLinux => Platform.isLinux;
  static bool get isAndroid => Platform.isAndroid;
  static bool get isIOS => Platform.isIOS;
  static T select<T>({required T desktop, required T mobile}) => isDesktop ? desktop : mobile;

  /// Cached Android API level, 0 on non-Android platforms.
  ///
  /// Guards for platform services that only exist from a certain API level on
  /// (e.g. getifaddrs from Android 7): calling them on older devices crashes
  /// the process natively, which no Dart try/catch can intercept.
  static int? _cachedAndroidSdkInt;

  static Future<int> androidSdkInt() async {
    if (!Platform.isAndroid) return 0;
    final cached = _cachedAndroidSdkInt;
    if (cached != null) return cached;
    final info = await DeviceInfoPlugin().androidInfo;
    return _cachedAndroidSdkInt = info.version.sdkInt;
  }
}
