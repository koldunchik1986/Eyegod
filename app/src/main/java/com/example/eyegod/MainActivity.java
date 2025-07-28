package com.example.eyegod;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText editTextQuery;
    private Button buttonSearch, buttonAddFile;
    private TextView textViewResults;

    private static final int REQUEST_CODE_PERMISSION = 100;
    private File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Для выбора файла
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
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

        // Проверка разрешений
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
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "application/csv"});
        filePickerLauncher.launch(intent);
    }

    private void importAndNormalizeCSV(Uri uri) {
        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                List<String[]> records = parseInputCSV(inputStream);
                inputStream.close();

                String fileName = "imported_" + System.currentTimeMillis() + ".csv";
                File outputFile = new File(csvDir, fileName);

                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outputFile))) {
                    for (String[] row : records) {
                        writer.write(String.join(";", row) + "\n");
                    }
                }

                mainHandler.post(() ->
                        Toast.makeText(this, "Файл добавлен и нормализован: " + fileName, Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Ошибка обработки файла: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private List<String[]> parseInputCSV(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        List<String[]> normalizedData = new ArrayList<>();
        String line;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Определяем разделитель
            String delimiter = line.contains(";") ? ";" : "\\|";

            String[] parts = line.split(delimiter, -1);
            if (parts.length != 4) continue; // Пропускаем некорректные

            // Пробуем определить порядок полей по заголовкам или содержимому
            String tel = "", name = "", tgId = "", email = "";

            // Попробуем угадать порядок
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

            // Если не удалось — используем как есть (наугад по позициям)
            if (name.isEmpty() || tel.isEmpty() || email.isEmpty() || tgId.isEmpty()) {
                // Попробуем по порядку: предположим, что это один из шаблонов
                if (line.contains("tel") || line.contains("phone") || parts[0].matches(".*\\d.*")) {
                    // Вариант 1: tel;email;tg_id;name
                    tel = parts[0].trim();
                    email = parts[1].trim();
                    tgId = parts[2].trim();
                    name = parts[3].trim();
                } else if (line.contains("tg") || parts[0].startsWith("@")) {
                    // Вариант 2: tg_id;email;name;tel
                    tgId = parts[0].trim();
                    email = parts[1].trim();
                    name = parts[2].trim();
                    tel = parts[3].trim();
                } else {
                    // Дефолт: оставляем как есть
                    tel = parts[0]; name = parts[1]; tgId = parts[2]; email = parts[3];
                }
            }

            // Приводим к единому формату: tel;name;tg_id;email
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
                finalText = String.join("\n\n", results);
            }

            runOnUiThread(() -> textViewResults.setText(finalText));
        }).start();
    }

    private void copySamplesFromAssets() {
        // Опционально: скопировать примеры из assets/csv/
        // Реализуется аналогично предыдущему, если нужно
    }
}