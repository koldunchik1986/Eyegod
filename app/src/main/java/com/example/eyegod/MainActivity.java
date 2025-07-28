package com.example.eyegod;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private EditText editTextQuery;
    private Button buttonSearch, buttonAddFile;
    private TextView textViewResults;
    private File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

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
        File csvDir = new File(getExternalFilesDir(null), "csv");
        if (!csvDir.exists()) {
            csvDir.mkdirs();
            copyCsvFromAssets(); // Добавь этот метод

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }

        buttonSearch.setOnClickListener(v -> startSearch());
        buttonAddFile.setOnClickListener(v -> pickFile());
    }
        private void copyCsvFromAssets() {
            AssetManager assetManager = getAssets();
            try {
                String[] files = assetManager.list("csv");
                if (files != null) {
                    for (String filename : files) {
                        InputStream in = assetManager.open("csv/" + filename);
                        File outFile = new File(getExternalFilesDir("csv"), filename);
                        FileOutputStream out = new FileOutputStream(outFile);
                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        in.close();
                        out.close();
                    }
                }
            } catch (IOException e) {
                Toast.makeText(this, "Ошибка копирования: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // УДАЛИТЬ: intent.setType("*/*");

        // Вместо этого — указать точные MIME-типы
        String[] mimeTypes = {"text/csv", "application/csv", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.setType("text/csv"); // Это сработает как "фильтр по умолчанию"

        filePickerLauncher.launch(intent);
    }

    private void importAndNormalizeCSV(Uri uri) {
        new Thread(() -> {
            try {
                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "imported_" + System.currentTimeMillis() + ".csv";
                }
                if (!fileName.toLowerCase().endsWith(".csv")) {
                    fileName += ".csv";
                }

                File targetFile = new File(csvDir, fileName);
                File finalFile = getUniqueFile(targetFile);

                InputStream inputStream = getContentResolver().openInputStream(uri);
                List<String[]> records = parseInputCSV(inputStream);
                inputStream.close();

                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(finalFile))) {
                    for (String[] row : records) {
                        writer.write(String.join(";", row) + "\n");
                    }
                }

                mainHandler.post(() ->
                    Toast.makeText(this, "Файл добавлен: " + finalFile.getName(), Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        try {
            String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
            android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        } catch (Exception e) { }
        return fileName;
    }

    private File getUniqueFile(File file) {
        File parent = file.getParentFile();
        String name = file.getName();
        String nameWithoutExt = name.substring(0, name.lastIndexOf('.'));
        String ext = name.substring(name.lastIndexOf('.'));
        int counter = 1;
        File newFile = file;
        while (newFile.exists()) {
            newFile = new File(parent, nameWithoutExt + "_" + counter + ext);
            counter++;
        }
        return newFile;
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
        String query = editTextQuery.getText().toString().trim().toLowerCase();
        if (query.isEmpty()) {
            runOnUiThread(() -> textViewResults.setText("Введите запрос."));
            return;
        }

        textViewResults.setText("Поиск...");

        new Thread(() -> {
            List<String> results = new ArrayList<>();
            File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));

            if (files != null) {
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
                        results.add("Ошибка: " + file.getName());
                    }
                }
            }

            String finalText = results.isEmpty()
                ? "Ничего не найдено по запросу: " + query
                : String.join("\n\n", results);

            runOnUiThread(() -> textViewResults.setText(finalText));
        }).start();
    }
}