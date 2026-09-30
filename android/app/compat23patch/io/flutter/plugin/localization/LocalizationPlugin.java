// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// Vendored from Flutter 3.47.5 (engine af7e796e16) with the API 24 guards
// restored. Upstream removed them when the default minSdk went to 24, but this
// TV build still ships to Android 6.0 (API 23) where
// Configuration.getLocales() does not exist — without the guards the engine
// crashes with NoSuchMethodError inside the FlutterEngine constructor. The
// class is recompiled and swapped into the flutter_embedding jar at build
// time; see patchFlutterEmbedding tasks in build.gradle.kts. When upstream
// changes this class's API surface, refresh this copy from the matching tag.

package io.flutter.plugin.localization;

import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import io.flutter.embedding.engine.systemchannels.LocalizationChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android implementation of the localization plugin. */
public class LocalizationPlugin {
  private final LocalizationChannel localizationChannel;
  private final Context context;

  final LocalizationChannel.LocalizationMessageHandler localizationMessageHandler =
      new LocalizationChannel.LocalizationMessageHandler() {
        @Override
        public String getStringResource(String key, String localeString) {
          Context localContext = context;
          String stringToReturn = null;

          if (localeString != null) {
            Locale locale = localeFromString(localeString);

            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(locale);
            localContext = context.createConfigurationContext(config);
          }

          String packageName = context.getPackageName();
          int resId = localContext.getResources().getIdentifier(key, "string", packageName);
          if (resId != 0) {
            // 0 means the resource is not found.
            stringToReturn = localContext.getResources().getString(resId);
          }

          return stringToReturn;
        }
      };

  public LocalizationPlugin(
      Context context, LocalizationChannel localizationChannel) {

    this.context = context;
    this.localizationChannel = localizationChannel;
    this.localizationChannel.setLocalizationMessageHandler(localizationMessageHandler);
  }

  /**
   * Computes the {@link Locale} in supportedLocales that best matches the user's preferred locales.
   *
   * <p>FlutterEngine must be non-null when this method is invoked.
   */
  @SuppressWarnings("deprecation")
  public Locale resolveNativeLocale(List<Locale> supportedLocales) {
    if (supportedLocales == null || supportedLocales.isEmpty()) {
      return null;
    }

    // Android improved the localization resolution algorithms after API 24 (7.0, Nougat).
    // See https://developer.android.com/guide/topics/resources/multilingual-support
    //
    // LanguageRange and Locale.lookup was added in API 26 and is the preferred way to
    // select a locale. Pre-API 26, we implement a manual locale resolution.
    if (Build.VERSION.SDK_INT >= API_LEVELS.API_26) {
      // Modern locale resolution using LanguageRange
      // https://developer.android.com/guide/topics/resources/multilingual-support#postN
      List<Locale.LanguageRange> languageRanges = new ArrayList<>();
      LocaleList localeList = context.getResources().getConfiguration().getLocales();
      int localeCount = localeList.size();
      for (int index = 0; index < localeCount; ++index) {
        Locale locale = localeList.get(index);
        // Convert locale string into language range format.
        String fullRange = locale.getLanguage();
        if (!locale.getScript().isEmpty()) {
          fullRange += "-" + locale.getScript();
        }
        if (!locale.getCountry().isEmpty()) {
          fullRange += "-" + locale.getCountry();
        }
        languageRanges.add(new Locale.LanguageRange(fullRange));
        languageRanges.add(new Locale.LanguageRange(locale.getLanguage()));
        languageRanges.add(new Locale.LanguageRange(locale.getLanguage() + "-*"));
      }
      Locale platformResolvedLocale = Locale.lookup(languageRanges, supportedLocales);
      if (platformResolvedLocale != null) {
        return platformResolvedLocale;
      }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      // Modern locale resolution without languageRange
      // https://developer.android.com/guide/topics/resources/multilingual-support#postN
      LocaleList localeList = context.getResources().getConfiguration().getLocales();
      for (int index = 0; index < localeList.size(); ++index) {
        Locale preferredLocale = localeList.get(index);
        // Look for exact match.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.equals(locale)) {
            return locale;
          }
        }
        // Look for exact language only match.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.toLanguageTag())) {
            return locale;
          }
        }
        // Look for any locale with matching language.
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.getLanguage())) {
            return locale;
          }
        }
      }
    } else {
      // Pre-Android 7: Configuration.getLocales() does not exist, fall back to
      // the single deprecated locale. Same resolution rules, one candidate.
      Locale preferredLocale = context.getResources().getConfiguration().locale;
      if (preferredLocale != null) {
        for (Locale locale : supportedLocales) {
          if (preferredLocale.equals(locale)) {
            return locale;
          }
        }
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.toLanguageTag())) {
            return locale;
          }
        }
        for (Locale locale : supportedLocales) {
          if (preferredLocale.getLanguage().equals(locale.getLanguage())) {
            return locale;
          }
        }
      }
    }
    return supportedLocales.get(0);
  }

  /**
   * Send the current {@link Locale} configuration to Flutter.
   *
   * <p>FlutterEngine must be non-null when this method is invoked.
   */
  @SuppressWarnings("deprecation")
  public void sendLocalesToFlutter(Configuration config) {
    List<Locale> locales = new ArrayList<>();
    // Configuration.getLocales() only exists from API 24; this method runs from
    // the FlutterEngine constructor, so the guard is what keeps Android 6.0
    // alive. Upstream dropped it together with the default minSdk bump to 24.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      LocaleList localeList = config.getLocales();
      int localeCount = localeList.size();
      for (int index = 0; index < localeCount; ++index) {
        Locale locale = localeList.get(index);
        locales.add(locale);
      }
    } else {
      //noinspection deprecation
      Locale locale = config.locale;
      if (locale != null) {
        locales.add(locale);
      }
    }

    localizationChannel.sendLocales(locales);
  }

  /**
   * Computes the {@link Locale} from the provided {@code String} with format
   * language[-script][-region][-...], where script is an alphabet string of length 4, and region is
   * either an alphabet string of length 2 or a digit string of length 3.
   */
  public static Locale localeFromString(String localeString) {
    Locale.Builder localeBuilder = new Locale.Builder();

    // Pre-API 21, we fall back to manually parsing the locale tag.
    // Normalize the locale string, replace all underscores with hyphens.
    String[] parts = localeString.replace('_', '-').split("-");

    // Assume the first part is always the language code.
    localeBuilder.setLanguage(parts[0]);

    int index = 1;
    if (parts.length > index && parts[index].length() == 4) {
      localeBuilder.setScript(parts[index]);
      index++;
    }
    if (parts.length > index && parts[index].length() >= 2 && parts[index].length() <= 3) {
      localeBuilder.setRegion(parts[index]);
      index++;
    }
    // Ignore the rest of the locale for this purpose.
    return localeBuilder.build();
  }
}
