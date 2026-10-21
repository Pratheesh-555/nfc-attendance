# NFC Attendance

Tap a college ID card on an Android phone → it reads the 9-digit reg number and
marks that student **P** in your Google Sheet, highlighting the row green.

There are two halves: the **Android app** (reads the card) and a small
**Google Sheet script** (marks attendance). Set up the script first.

---

## Part A — Google Sheet (5 minutes, do this once)

1. Open your attendance Google Sheet. Make sure **row 1 is the header row**
   and one column holds the 9-digit reg numbers (any column — the script
   searches all of them).
2. Menu: **Extensions → Apps Script**.
3. Delete whatever is there, paste the contents of `apps-script/Code.gs`, and
   click the **Save** icon.
4. Click **Deploy → New deployment**.
   - Gear icon → **Web app**.
   - **Execute as:** Me
   - **Who has access:** Anyone
   - **Deploy**. Approve the permissions when Google asks (it's your own script).
5. Copy the **Web app URL** it shows you. It ends in `/exec`. You'll paste this
   into the app.

> If you later change the sheet, you don't need to redeploy. Only redeploy if
> you edit the script code.

---

## Part B — Build the app (GitHub builds it for you, no Android Studio)

1. Create a new **public** GitHub repo.
2. Upload every file in this folder, keeping the structure (easiest: upload the
   whole folder, or commit it with git).
3. Go to the repo's **Actions** tab. The "Build APK" job runs automatically on
   push (or click **Run workflow**).
4. When it finishes (about 3–5 min), open the run and download the
   **nfc-attendance-apk** artifact at the bottom. Inside is `app-debug.apk`.
5. Copy that APK to your phone and install it. You'll need to allow
   "install from unknown sources" for your file manager — that's normal for
   an app you built yourself.

---

## Part C — Use it

1. Open the app. Paste the Web App URL into the first box, tap
   **Save & Load sheets**.
2. Pick the sheet (tab) you want from the dropdown.
3. Hold a college ID card to the back of the phone. It reads the reg number,
   marks **P**, greens the row, and buzzes.
   - Already present → it says so and buzzes twice.
   - Reg not in the sheet → it tells you.
   - Card won't read → type the 9 digits into the manual box and tap **Mark**.

---

## Notes

- The app reads the card's **NDEF text record** using Android's standard NFC
  reader. No card keys, no cloning — just the reg number the card already
  publishes.
- A card that stores nothing readable (only a serial number) can't be read by
  any app this way; use the manual box for those, or ask your college for the
  card-serial → reg-number list.
- The debug APK is signed with a throwaway debug key — fine for your own use.
  For wider distribution you'd sign a release build, but that's not needed here.
