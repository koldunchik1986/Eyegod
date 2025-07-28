package com.example.eyegod;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.documentfile.provider.DocumentFile;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Eyegod";

    private EditText editTextQuery;
    private Button buttonSearch, buttonSelectFolder;
    private TextView textViewResults;
    private java.io.File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String PREFS_NAME = "EyegodPrefs";
    private static final String KEY_CSV_FOLDER_URI = "csv_folder_uri";
    private Uri csvFolderUri;

    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    saveCsvFolderUri(treeUri);
                    Toast.makeText(this, "Папка сохранена. Сканирую файлы...", Toast.LENGTH_LONG).show();
                    scanAndImportFromUri(treeUri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextQuery = findViewById(R.id.editTextQuery);
        buttonSearch = findViewById(R.id.buttonSearch);
        buttonSelectFolder = findViewById(R.id.buttonSelectFolder);
        buttonSelectFolder.setOnClickListener(v -> pickCsvFolder());
        textViewResults = findViewById(R.id.textViewResults);

        csvDir = new java.io.File(getExternalFilesDir(null), "csv");
        if (!csvDir.exists()) {
            csvDir.mkdirs();
        }

        csvFolderUri = loadCsvFolderUri();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        } else {
            initApp();
        }
    }

    private void initApp() {
        Log.d(TAG, "Инициализация приложения");
        copyCsvFromAssets();

        if (csvFolderUri != null) {
            Log.d(TAG, "Автозагрузка из папки: " + csvFolderUri.toString());
            scanAndImportFromUri(csvFolderUri);
        } else {
            showFolderPickerDialog();
        }

        buttonSearch.setOnClickListener(v -> startSearch());
    }

    private void showFolderPickerDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Укажите папку с CSV")
                .setMessage("Выберите папку, где хранятся ваши CSV-файлы.")
                .setPositiveButton("Выбрать", (d, w) -> pickCsvFolder())
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Пропустить", (d, w) -> {})
                .show();
    }

    private void pickCsvFolder() {
        Log.d(TAG, "Открывается выбор папки");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            folderPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при запуске выбора папки", e);
            Toast.makeText(this, "Не удалось открыть проводник", Toast.LENGTH_LONG).show();
        }
    }

    private void saveCsvFolderUri(Uri uri) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_CSV_FOLDER_URI, uri.toString())
                .apply();
        Log.d(TAG, "URI папки сохранён: " + uri.toString());
    }

    private Uri loadCsvFolderUri() {
        String uriString = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_CSV_FOLDER_URI, null);
        Log.d(TAG, "Загрузка URI папки: " + uriString);
        return uriString != null ? Uri.parse(uriString) : null;
    }

    private void scanAndImportFromUri(Uri treeUri) {
        Log.d(TAG, "Сканирование папки: " + treeUri);
        try {
            DocumentFile documentDir = DocumentFile.fromTreeUri(this, treeUri);
            if (documentDir != null && documentDir.isDirectory()) {
                Log.d(TAG, "Найдено файлов: " + documentDir.listFiles().length);
                for (DocumentFile file : documentDir.listFiles()) {
                    if (file.getName() != null && file.getName().toLowerCase().endsWith(".csv")) {
                        java.io.File destFile = new java.io.File(csvDir, file.getName());
                        if (!destFile.exists()) {
                            Log.d(TAG, "Импортируется: " + file.getName());
                            normalizeAndCopyDocumentFile(file, destFile);
                        } else {
                            Log.d(TAG, "Уже существует: " + file.getName());
                        }
                    }
                }
            } else {
                Log.w(TAG, "Папка не найдена или пуста");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при сканировании папки", e);
            Toast.makeText(this, "Ошибка доступа к папке", Toast.LENGTH_LONG).show();
        }
    }

    private void normalizeAndCopyDocumentFile(DocumentFile srcFile, java.io.File destFile) {
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(srcFile.getUri());
                 OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(destFile))) {

                List<String[]> records = parseInputCSV(in);
                for (String[] row : records) {
                    writer.write(String.join(";", row) + "\n");
                }

                mainHandler.post(() -> {
                    Log.d(TAG, "Файл импортирован: " + srcFile.getName());
                    Toast.makeText(this, "Импортирован: " + srcFile.getName(), Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Ошибка при копировании: " + srcFile.getName(), e);
                mainHandler.post(() ->
                        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void copyCsvFromAssets() {
        try {
            String[] files = getAssets().list("csv");
            if (files != null) {
                for (String filename : files) {
                    InputStream in = getAssets().open("csv/" + filename);
                    java.io.File outFile = new java.io.File(csvDir, filename);
                    if (outFile.exists()) {
                        in.close();
                        continue;
                    }
                    FileOutputStream out = new FileOutputStream(outFile);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    out.close();
                    Log.d(TAG, "Копирован из assets: " + filename);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка копирования из assets", e);
            Toast.makeText(this, "Ошибка копирования из assets: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private List<String[]> parseInputCSV(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        List<String[]> normalizedData = new ArrayList<>();
        String line;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String delimiter = line.contains(";") ? ";" : "\\|";
            String[] parts = line.split(delimiter, -1);
            if (parts.length != 4) continue;

            String tel = "", name = "", tgId = "", email = "";

            for (int i = 0; i < 4; i++) {
                String field = parts[i].trim();
                if (field.matches(".*[а-яА-Яa-zA-Z]{2,}.*") && !field.contains("@") && !field.contains("+") && !field.matches(".*\\d.*")) {
                    name = field;
                } else if (field.contains("@")) {
                    email = field;
                } else if (field.contains("t.me/") || field.startsWith("@") || field.matches("^[a-zA-Z][a-zA-Z0-9._]{4,32}$")) {
                    tgId = field;
                } else if (field.matches(".*\\d.*")) {
                    tel = field;
                }
            }

            if (name.isEmpty() || tel.isEmpty() || email.isEmpty() || tgId.isEmpty()) {
                if (line.contains("tel") || parts[0].matches(".*\\d.*")) {
                    tel = parts[0]; email = parts[1]; tgId = parts[2]; name = parts[3];
                } else if (line.contains("tg") || parts[0].startsWith("@")) {
                    tgId = parts[0]; email = parts[1]; name = parts[2]; tel = parts[3];
                } else {
                    tel = parts[0]; name = parts[1]; tgId = parts[2]; email = parts[3];
                }
            }

            normalizedData.add(new String[]{
                    cleanField(tel),
                    cleanField(name),
                    cleanField(tgId),
                    cleanField(email)
            });
        }
        reader.close();
        return normalizedData;
    }

    private String cleanField(String s) {
        return s == null ? "" : s.trim().replaceAll("^\"|\"$", "");
    }

    private void startSearch() {
        Log.d(TAG, "Поиск запущен");
        String query = editTextQuery.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            runOnUiThread(() -> textViewResults.setText("Введите запрос."));
            return;
        }

        textViewResults.setText("Поиск...");

        new Thread(() -> {
            List<String> results = new ArrayList<>();
            java.io.File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));

            Log.d(TAG, "Поиск по " + (files != null ? files.length : 0) + " файлам");

            if (files != null) {
                for (java.io.File file : files) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;

                            String[] parts = line.split(";", -1);
                            if (parts.length < 4) continue;

                            String tel = cleanField(parts[0]);
                            String name = cleanField(parts[1]);
                            String tgId = cleanField(parts[2]);
                            String email = cleanField(parts[3]);

                            String searchable = (tel + name + tgId + email).toLowerCase();
                            if (searchable.contains(query)) {
                                Log.d(TAG, "Найдено в файле: " + file.getName());
                                StringBuilder result = new StringBuilder();
                                result.append("База: ").append(file.getName()).append("\n");
                                result.append("ФИО: ").append(name.isEmpty() ? "отсутствует" : name).append("\n");
                                result.append("Телефон: ").append(tel.isEmpty() ? "отсутствует" : tel).append("\n");
                                result.append("Telegram: ").append(tgId.isEmpty() ? "отсутствует" : tgId).append("\n");
                                result.append("email: ").append(email.isEmpty() ? "отсутствует" : email);
                                results.add(result.toString());
                            }
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Ошибка чтения файла: " + file.getName(), e);
                        results.add("Ошибка: " + file.getName());
                    }
                }
            }

            String finalText = results.isEmpty()
                    ? "Ничего не найдено по запросу: " + query
                    : String.join("\n\n", results);

            runOnUiThread(() -> {
                textViewResults.setText(finalText);
                Log.d(TAG, "Результаты выведены: " + results.size() + " совпадений");
            });
        }).start();
    }
}