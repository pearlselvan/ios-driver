/*
 * Copyright 2012-2013 eBay Software Foundation and ios-driver committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.uiautomation.ios.utils;

import com.google.common.collect.ImmutableList;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.selenium.WebDriverException;
import org.uiautomation.ios.communication.device.DeviceType;
import org.uiautomation.ios.communication.device.DeviceVariation;
import org.uiautomation.ios.server.command.uiautomation.NewSessionNHandler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimulatorSettings {
    
  public static void main(String[] args) throws Exception {
    ImmutableList<String> sdkVersions = ImmutableList.of("6.1", "7.0");
    for (String sdkVersion : sdkVersions) {
      SimulatorSettings settings = new SimulatorSettings(sdkVersion);
      String exactSdkVersion = settings.exactSdkVersion;
      
      String globalPreferences = "not available";
      try {
        globalPreferences = new PlistFileUtils(settings.globalPreferencePlist).toJSON().toString(2);
      } catch (WebDriverException e) {
      }
      System.out.println(String.format("\nglobalPreferences %s (%s): %s",
          sdkVersion, exactSdkVersion, globalPreferences));
      
      String safariPreferences = "not available";
      try {
        safariPreferences = new PlistFileUtils(settings.getMobileSafariPreferencesFile()).toJSON().toString(2);
      } catch (WebDriverException e) {
      }
      System.out.println(String.format("\nsafariPreferences %s (%s): %s",
          sdkVersion, exactSdkVersion, safariPreferences));
      
      String miscPreferences = "not available";
      String miscPlistFile = "Applications/90428432-F387-4323-B697-3596C419CD1B/Library/Safari/SuspendState.plist";
      try {
        miscPreferences = new PlistFileUtils(new File(settings.contentAndSettingsFolder, miscPlistFile))
            .toJSON().toString(2);
      } catch (WebDriverException e) {
      }
      System.out.println(String.format("\n%s %s (%s): %s",
          miscPlistFile, sdkVersion, exactSdkVersion, miscPreferences));
      
      // showAllPlistFiles(settings.contentAndSettingsFolder);
    }
  }
  
  private static void showAllPlistFiles(File rootDir) {
    File[] files = rootDir.listFiles();
    if (files == null)
      return;
    for (File file: files) {
      if (file.isDirectory()) {
        showAllPlistFiles(file);
      } else if (file.getName().endsWith(".plist")) {
        showPlistFile(file);
      }
    }
  }
  
  private static void showPlistFile(File file) {
    try {
      String plist = new PlistFileUtils(file).toJSON().toString(2);
      if (plist.contains("cnn"))
        System.out.println("\n" + file.getAbsolutePath() + ": " + plist);
    } catch (Exception e) {
      System.err.println("exception reading " + file.getAbsolutePath() + ": " + e);
    }
  }

  //

  private static final Logger log = Logger.getLogger(SimulatorSettings.class.getName());
  private static final String PLUTIL = "/usr/bin/plutil";
  private static final String TEMPLATE = "/globalPlist.json";

  private final String exactSdkVersion;
  private final File contentAndSettingsFolder;
  private final File globalPreferencePlist;

  public SimulatorSettings(String sdkVersion) {
    this.exactSdkVersion = ClassicCommands.getSimulatorProductVersion(sdkVersion);
    this.contentAndSettingsFolder = getContentAndSettingsFolder();
    this.globalPreferencePlist = getGlobalPreferenceFile();
  }

  private String getExactSdkVersion(String sdkVersion) {
    // NOTE: this method gives the wrong version the first time is run on a clean
    // iPhone simulator dir (i.e. would return "7.0" instead of "7.0.3"
    File parentFolder = getContentAndSettingsParentFolder();
    if (!parentFolder.isDirectory()) {
      return sdkVersion;
    }
    int maxMinorVersion = -1;
    Pattern pattern = Pattern.compile(String.format("^%s.([0-9]+)$", sdkVersion));
    for (String child : parentFolder.list()) {
      Matcher matcher = pattern.matcher(child);
      if (matcher.matches()) {
        int minorVersion = Integer.parseInt(matcher.group(1));
        maxMinorVersion = Math.max(minorVersion, maxMinorVersion);
      }
    }
    if (maxMinorVersion != -1) {
      return String.format("%s.%d", sdkVersion, maxMinorVersion);
    }
    return sdkVersion;
  }

  public void setLocationPreference(boolean authorized, String bundleId) {
    File f = new File(contentAndSettingsFolder + "/Library/Caches/locationd/", "clients.plist");

    try {
      JSONObject clients = new JSONObject();
      JSONObject options = new JSONObject();
      options.put("Whitelisted", false);
      options.put("BundleId", bundleId);
      options.put("Authorized", authorized);
      clients.put(bundleId, options);
      writeOnDisk(clients, f);
    } catch (Exception e) {
      throw new WebDriverException("cannot set location in " + f.getAbsolutePath(), e);
    }
  }

  /**
   * the default keyboard options aren't good for automation. For instance it automatically
   * capitalize the first letter of sentences etc. Getting rid of all that to have the keyboard
   * execute requests without changing them.
   */
  public void setKeyboardOptions() {
    File folder = new File(contentAndSettingsFolder + "/Library/Preferences/");
    File preferenceFile = new File(folder, "com.apple.Preferences.plist");

    try {
      JSONObject preferences = new JSONObject();
      preferences.put("KeyboardAutocapitalization", false);
      preferences.put("KeyboardAutocorrection", false);
      preferences.put("KeyboardCapsLock", false);
      preferences.put("KeyboardCheckSpelling", false);
      writeOnDisk(preferences, preferenceFile);
    } catch (Exception e) {
      throw new WebDriverException("cannot set options in " + preferenceFile.getAbsolutePath(), e);
    }
  }

  public void setMobileSafariOptions() {
    File preferenceFile = getMobileSafariPreferencesFile();
    try {
      JSONObject preferences = new JSONObject();
      preferences.put("WarnAboutFraudulentWebsites", false);
      writeOnDisk(preferences, preferenceFile);
    } catch (Exception e) {
      throw new WebDriverException("cannot set options in " + preferenceFile.getAbsolutePath(), e);
    }
  }
  
  private File getMobileSafariPreferencesFile() {
      File folder = new File(contentAndSettingsFolder + "/Library/Preferences/");
      return new File(folder, "com.apple.mobilesafari.plist");
  }

  /**
   * set the emulator to the given locale.Required a clean context (can only be done after "reset
   * content and settings" )
   *
   * @param locale   fr_FR
   * @param language fr
   */
  public void setL10N(String locale, String language) {
    try {
      JSONObject plistJSON = getPreferenceFile(locale, language);
      writeOnDisk(plistJSON, globalPreferencePlist);
    } catch (Exception e) {
      throw new WebDriverException("cannot configure simulator", e);
    }
  }

  /**
   * update the preference to have the simulator start in the correct more ( ie retina vs normal,
   * iphone screen size ).
   */
  public void setVariation(DeviceType device, DeviceVariation variation, String desiredSDKVersion)
      throws WebDriverException {
    String value = getSimulateDeviceValue(device, variation, desiredSDKVersion);
    setDefaultSimulatorPreference("SimulateDevice", value);
  }
  
  public void setSimulatorScale(String scale) {
    if (scale != null) {
      // error check scale value
      float fScale = Float.parseFloat(scale);
      if (fScale <= 0)
        throw new WebDriverException("invalid simulator scale: " + scale);
      setDefaultSimulatorPreference("SimulatorWindowLastScale", scale);
    }
  }

  /**
   * update the preference of the simulator. Similar to using the IOS Simulator menu > Hardware >
   * [DeviceType | Version ]
   */
  private void setDefaultSimulatorPreference(String key, String value) {
    List<String> com = new ArrayList<>();
    com.add("defaults");
    com.add("write");
    com.add("com.apple.iphonesimulator");
    com.add(key);
    com.add(String.format("\"%s\"", value));

    Command updatePreference = new Command(com, true);
    updatePreference.executeAndWait();
  }

  /**
   * Does what IOS Simulator - Reset content and settings menu does, by deleting the files on disk.
   * The simulator shouldn't be running when that is done.
   */
  public void resetContentAndSettings() {
    if (hasContentAndSettingsFolder()) {
      boolean ok = deleteRecursive(getContentAndSettingsFolder());
      if (!ok) {
        System.err.println("cannot delete content and settings folder " + contentAndSettingsFolder);
      }
    }
    boolean ok = contentAndSettingsFolder.mkdir();
    if (!ok) {
      System.err.println("couldn't re-create" + contentAndSettingsFolder);
    }
  }

  private File getContentAndSettingsParentFolder() {
    String home = System.getProperty("user.home");
    return new File(home, "Library/Application Support/iPhone Simulator");
  }

  private File getContentAndSettingsFolder() {
    return new File(getContentAndSettingsParentFolder(), exactSdkVersion);
  }

  private boolean deleteRecursive(File folder) {
    if (folder.isDirectory()) {
      if (isSymLink(folder)) {
        return folder.delete();
      }
      for (String child : folder.list()) {
        File delMe = new File(folder, child);
        boolean success = deleteRecursive(delMe);
        if (!success) {
          log.warning("cannot delete " + delMe
                      + ".Are you trying to start a test while a simulator is still running ?");
        }
      }
    }
    return folder.delete();
  }

  private boolean isSymLink(File folder) {
    return Files.isSymbolicLink(Paths.get(folder.toURI()));
  }

  private boolean hasContentAndSettingsFolder() {
    return getContentAndSettingsFolder().exists();
  }

  private String getSimulateDeviceValue(DeviceType device, DeviceVariation variation, String desiredSDKVersion)
      throws WebDriverException {
    if (!DeviceVariation.compatibleWithSDKVersion(device, variation, desiredSDKVersion)) {
      throw new WebDriverException(String.format("%s incompatible with SDK %s",
          DeviceVariation.deviceString(device, variation),
          desiredSDKVersion));
    }
    return DeviceVariation.deviceString(device, variation);
  }

  private JSONObject loadGlobalPreferencesTemplate() throws JSONException, IOException {
    InputStream is = NewSessionNHandler.class.getResourceAsStream(TEMPLATE);
    StringWriter writer = new StringWriter();
    IOUtils.copy(is, writer, "UTF-8");
    String content = writer.toString();
    return new JSONObject(content);
  }

  private JSONObject getPreferenceFile(String locale, String language)
      throws JSONException, IOException {
    JSONObject res = loadGlobalPreferencesTemplate();
    JSONArray languages = new JSONArray();
    languages.put(language);
    res.put("AppleLanguages", languages);
    res.put("AppleLocale", locale);
    return res;
  }

  private File getGlobalPreferenceFile() {
    File folder = new File(contentAndSettingsFolder + "/Library/Preferences/");
    return new File(folder, ".GlobalPreferences.plist");
  }

  // TODO use plist utils.
  private void writeOnDisk(JSONObject plistJSON, File destination)
      throws IOException, JSONException {
    if (destination.exists()) {
      // To be on the safe side. If the emulator already runs, it won't work
      // anyway.
      throw new WebDriverException(globalPreferencePlist + " already exists. Cannot create it.");
    }

    // make sure the folder is ready for the plist file
    destination.getParentFile().mkdirs();

    checkPlUtil();

    File from = createTmpFile(plistJSON);

    List<String> command = new ArrayList<>();
    command.add(PLUTIL);
    command.add("-convert");
    command.add("binary1");
    command.add("-o");
    command.add(destination.getAbsolutePath());
    command.add(from.getAbsolutePath());

    ProcessBuilder b = new ProcessBuilder(command);
    int i;
    try {
      Process p = b.start();
      i = p.waitFor();
    } catch (Exception e) {
      throw new WebDriverException("failed to run " + command.toString(), e);
    }
    if (i != 0) {
      throw new WebDriverException("conversion to binary plist failed. exitCode=" + i);
    }
  }

  private File createTmpFile(JSONObject content) throws IOException, JSONException {
    File res = File.createTempFile("global", ".json");
    BufferedWriter out = new BufferedWriter(new FileWriter(res));
    out.write(content.toString(2));
    out.close();
    return res;
  }

  private void checkPlUtil() {
    File f = new File(PLUTIL);
    if (!f.exists() || !f.canExecute()) {
      throw new WebDriverException("Cannot access " + PLUTIL);
    }
  }

  public void installTrustStore(String trustStore) {
    if (trustStore == null)
      return;
          
    // executes:
    // mkdir ~/"Library/Application Support/iPhone Simulator/7.0/Library/Keychains"
    // cp libs/ios/TrustStore.sqlite3 ~/"Library/Application Support/iPhone Simulator/7.0/Library/Keychains"
    File keychainsDir = new File(contentAndSettingsFolder + "/Library/Keychains");
    log.info("installing -trustStore: " + trustStore + " in " + keychainsDir.getAbsolutePath());
    File sourceFile = new File(trustStore);
    if (!sourceFile.exists()) {
      log.severe("-trustStore: source trust store file doesn't exist: " + sourceFile.getAbsolutePath());
      return;
    }
    File destFile = new File(keychainsDir, "TrustStore.sqlite3");
    try {
      if (!keychainsDir.exists()) {
        if (!keychainsDir.mkdir()) {
          log.severe("-trustStore: could not create Keychains dir: " + keychainsDir.getAbsolutePath());
          return;
        }
      }
      FileUtils.copyFile(sourceFile, destFile, false);
    } catch (Exception e) {
      log.severe("cannot install trust store file " + sourceFile.getAbsolutePath()
              + " to " + destFile.getAbsolutePath() + ": " + e);
    }
  }
}
