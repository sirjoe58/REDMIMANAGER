; Programma di installazione di Redmi Manager per Windows (Inno Setup)
#define AppVer GetEnv("APP_VERSION")
#if AppVer == ""
  #define AppVer "1.0.0"
#endif

[Setup]
AppId={{6F1C2B7E-5A41-4C2F-9E2B-7D3A1C5E9F20}
AppName=Redmi Manager
AppVersion={#AppVer}
AppPublisher=Sergio Mantovani
DefaultDirName={autopf}\Redmi Manager
DefaultGroupName=Redmi Manager
OutputDir=dist
OutputBaseFilename=RedmiManager-Setup
SetupIconFile=icona.ico
UninstallDisplayIcon={app}\RedmiManager.exe
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=lowest
LicenseFile=LEGGIMI_AVVERTENZE.txt
WizardStyle=modern
CloseApplications=force

[Languages]
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "desktopicon"; Description: "Crea un'icona sul desktop"; GroupDescription: "Icone:"
Name: "autostart"; Description: "Avvia Redmi Manager all'accensione del computer"; GroupDescription: "Avvio:"

[Files]
Source: "dist\RedmiManager.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "LEGGIMI_AVVERTENZE.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Redmi Manager"; Filename: "{app}\RedmiManager.exe"
Name: "{group}\Avvertenze"; Filename: "{app}\LEGGIMI_AVVERTENZE.txt"
Name: "{autodesktop}\Redmi Manager"; Filename: "{app}\RedmiManager.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "RedmiManager"; ValueData: """{app}\RedmiManager.exe"" --nascosto"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; consente al programma di ricevere i backup dai telefoni nella rete privata di casa
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Redmi Manager"" dir=in action=allow program=""{app}\RedmiManager.exe"" profile=private"; Flags: runhidden runascurrentuser; Check: IsAdminInstallMode
Filename: "{app}\RedmiManager.exe"; Description: "Avvia Redmi Manager"; Flags: nowait postinstall skipifsilent

[Code]
// Chiude Redmi Manager se e' aperto (anche nascosto), cosi' i file si possono sostituire
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM RedmiManager.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(800);
  Result := '';
end;
