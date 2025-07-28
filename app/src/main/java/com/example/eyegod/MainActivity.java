package com.example.eyegod;

import androidx.appcompat.app.AppCompatActivity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private EditText editTextQuery;
    private Button buttonSearch, buttonAddFile;
    private TextView textViewResults;
    private static final int REQUEST_CODE_PERMISSION = 100;
    private File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Uri lastPickedUri; // Для временного хранения URI выбранного файла

    // Для выбора файла
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        lastPickedUri = uri;
                        importAndNormalizeCSV(uri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextQuery = findViewById(R.id.editTextQuery);
        buttonSearch = findViewById(R.id.buttonSearch);
        buttonAddFile = findViewById(R.id.buttonAddFile);
        textViewResults = findViewById(R.id.textViewResults);

        csvDir = new File(getExternalFilesDir(null), "csv");
        if (!csvDir.exists()) csvDir.mkdirs();

        // Проверка разрешений (опционально для ACTION_OPEN_DOCUMENT)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_PERMISSION);
        }

        buttonSearch.setOnClickListener(v -> startSearch());
        buttonAddFile.setOnClickListener(v -> pickFile());

        // Копируем примеры из assets при первом запуске (если нужно)
        copySamplesFromAssets();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String[] mimeTypes = {
                "text/plain",
                "text/csv",
                "application/csv",
                "application/vnd.ms-excel",
                "*/*"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private void importAndNormalizeCSV(Uri uri) {
        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    mainHandler.post(() ->
                            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
                    );
                    return;
                }

                List<String[]> records = parseInputCSV(inputStream);
                inputStream.close();

                // Получаем оригинальное имя файла
                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty() || !fileName.toLowerCase().endsWith(".csv")) {
                    fileName = "imported_file.csv";
                }

                // Проверяем уникальность имени и содержимого
                File outputFile = getUniqueFileForSave(fileName);
                if (outputFile == null) {
                    final String finalFileName = fileName; // <-- Копия, которая effectively final
                    mainHandler.post(() ->
                            Toast.makeText(this, "Файл уже добавлен: " + finalFileName, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }

                // Сохраняем нормализованные данные
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outputFile))) {
                    for (String[] row : records) {
                        writer.write(String.join(";", row) + "\n");
                    }
                }

                mainHandler.post(() ->
                        Toast.makeText(this, "Файл добавлен: " + outputFile.getName(), Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() ->
                        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    // Получить оригинальное имя файла из URI
    private String getFileName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (result == null) {
            result = new File(uri.getPath()).getName();
        }
        return result;
    }

    // Генерация уникального имени файла с учётом содержимого
    private File getUniqueFileForSave(String originalFileName) {
        if (!originalFileName.toLowerCase().endsWith(".csv")) {
            originalFileName += ".csv";
        }

        File targetFile = new File(csvDir, originalFileName);
        String baseName = originalFileName.substring(0, originalFileName.length() - 4);
        int counter = 1;

        while (targetFile.exists()) {
            try {
                InputStream newStream = getContentResolver().openInputStream(lastPickedUri);
                if (newStream != null && filesContentEquals(targetFile, newStream)) {
                    return null; // Файлы идентичны — не сохраняем
                }
            } catch (IOException e) {
                // Ошибка при сравнении — продолжаем с новым именем
            }

            targetFile = new File(csvDir, baseName + "_" + counter + ".csv");
            counter++;
        }

        return targetFile;
    }

    // Сравнение содержимого двух файлов
    private boolean filesContentEquals(File file, InputStream inputStream) throws IOException {
        if (!file.exists()) return false;

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader1 = new BufferedReader(new InputStreamReader(fis));
             BufferedReader reader2 = new BufferedReader(new InputStreamReader(inputStream))) {

            String line1, line2 = null;
            while ((line1 = reader1.readLine()) != null) {
                line2 = reader2.readLine();
                if (line1.trim().equals(line2 == null ? "" : line2.trim())) {
                    continue;
                } else {
                    return false;
                }
            }
            return line2 == null;
        }
    }

    // Парсинг CSV: определяем разделитель и нормализуем порядок полей
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

            for (String field : parts) {
                field = field.trim();
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
                if (line.contains("tel") || line.contains("phone") || parts[0].matches(".*\\d.*")) {
                    tel = parts[0].trim();
                    email = parts[1].trim();
                    tgId = parts[2].trim();
                    name = parts[3].trim();
                } else if (line.contains("tg") || parts[0].startsWith("@")) {
                    tgId = parts[0].trim();
                    email = parts[1].trim();
                    name = parts[2].trim();
                    tel = parts[3].trim();
                } else {
                    tel = parts[0];
                    name = parts[1];
                    tgId = parts[2];
                    email = parts[3];
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
        String query = editTextQuery.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            runOnUiThread(() -> textViewResults.setText("Введите запрос."));
            return;
        }

        textViewResults.setText("Поиск...");
        new Thread(() -> {
            List<String> results = new ArrayList<>();
            File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));
            if (files == null) return;

            for (File file : files) {
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
                    results.add("Ошибка чтения: " + file.getName());
                }
            }

            String finalText;
            if (results.isEmpty()) {
                finalText = "Ничего не найдено по запросу: " + query;
            } else {
                finalText = String.join("\n", results);
            }

            runOnUiThread(() -> textViewResults.setText(finalText));
        }).start();
    }

    private void copySamplesFromAssets() {
        // Опционально: скопировать примеры из папки assets/csv/
        // Реализуется при необходимости
    }
}