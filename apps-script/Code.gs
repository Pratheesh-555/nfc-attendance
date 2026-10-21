/**
 * NFC Attendance — Google Sheet backend.
 *
 * Paste this into your sheet:  Extensions -> Apps Script, replace everything,
 * Save, then Deploy -> New deployment -> Web app:
 *     Execute as:  Me
 *     Who has access:  Anyone
 * Copy the /exec URL it gives you and paste that into the Android app.
 *
 * It assumes row 1 is the header row. It searches every column for the
 * reg number, so you don't have to tell it which column holds it.
 */

function doGet(e) {
  var action = (e && e.parameter && e.parameter.action) || '';
  try {
    if (action === 'tabs')  return tabs_();
    if (action === 'mark')  return mark_(e.parameter.sheet, e.parameter.reg);
    return json_({ status: 'error', message: 'unknown action' });
  } catch (err) {
    return json_({ status: 'error', message: String(err) });
  }
}

function tabs_() {
  var names = SpreadsheetApp.getActiveSpreadsheet().getSheets().map(function (s) {
    return s.getName();
  });
  return json_({ status: 'ok', tabs: names });
}

function mark_(sheetName, reg) {
  reg = String(reg || '').trim();
  if (!/^\d{9}$/.test(reg)) return json_({ status: 'error', message: 'bad reg' });

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = sheetName ? ss.getSheetByName(sheetName) : ss.getSheets()[0];
  if (!sh) return json_({ status: 'error', message: 'sheet not found' });

  var values = sh.getDataRange().getValues();
  if (values.length === 0) return json_({ status: 'notfound', reg: reg });

  // Find the row whose any cell equals the reg number (compared as text).
  var rowIdx = -1;
  for (var r = 0; r < values.length; r++) {
    for (var c = 0; c < values[r].length; c++) {
      if (String(values[r][c]).trim() === reg) { rowIdx = r; break; }
    }
    if (rowIdx >= 0) break;
  }
  if (rowIdx < 0) return json_({ status: 'notfound', reg: reg });

  // Find or create the "Attendance" column (header in row 1).
  var headers = values[0];
  var attCol = -1;
  for (var i = 0; i < headers.length; i++) {
    if (String(headers[i]).trim().toLowerCase() === 'attendance') { attCol = i + 1; break; }
  }
  if (attCol < 0) {
    attCol = sh.getLastColumn() + 1;
    sh.getRange(1, attCol).setValue('Attendance').setFontWeight('bold');
  }

  var sheetRow = rowIdx + 1;
  var cur = String(sh.getRange(sheetRow, attCol).getValue()).trim().toUpperCase();
  var already = (cur === 'P');

  sh.getRange(sheetRow, attCol).setValue('P');

  // Highlight the whole used part of that row green.
  var width = Math.max(sh.getLastColumn(), attCol);
  sh.getRange(sheetRow, 1, 1, width).setBackground('#b7e1cd');

  return json_({ status: already ? 'already' : 'ok', row: sheetRow, reg: reg });
}

function json_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
