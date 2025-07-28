package com.example.eyegod;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.database.Cursor;
import android.provider.OpenableColumns;
import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import android.app.AlertDialog;
import android.content.DialogInterface;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.*;
import java.util.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private EditText editTextQuery;
    private Button buttonSearch, buttonAddFile, buttonShowFiles;
    private TextView textViewResults, textViewVersion;
    private ListView listViewFiles;
    private LinearLayout layoutFileActions;
    private Button buttonDeleteFile, buttonRenameFile, buttonShareFile;
    private static final int REQUEST_CODE_PERMISSION = 100;
    private File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Uri lastPickedUri;
    private File selectedFile = null;

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
        buttonShowFiles = findViewById(R.id.buttonShowFiles);
        listViewFiles = findViewById(R.id.listViewFiles);
        layoutFileActions = findViewById(R.id.layoutFileActions);
        buttonDeleteFile = findViewById(R.id.buttonDeleteFile);
        buttonRenameFile = findViewById(R.id.buttonRenameFile);
        buttonShareFile = findViewById(R.id.buttonShareFile);
        textViewVersion = findViewById(R.id.textViewVersion); // ✅

        csvDir = new File(getExternalFilesDir(null), "csv");
        if (!csvDir.exists()) csvDir.mkdirs();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_PERMISSION);
        }

        buttonSearch.setOnClickListener(v -> startSearch());
        buttonAddFile.setOnClickListener(v -> pickFile());
        buttonShowFiles.setOnClickListener(v -> showFilesList());

        buttonDeleteFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            new AlertDialog.Builder(this)
                    .setTitle("Удалить файл")
                    .setMessage("Удалить файл:\n" + selectedFile.getName() + "?")
                    .setPositiveButton("Да", (d, w) -> {
                        if (selectedFile.delete()) {
                            Toast.makeText(this, "Файл удалён", Toast.LENGTH_SHORT).show();
                            selectedFile = null;
                            layoutFileActions.setVisibility(View.GONE);
                            showFilesList();
                            if (listViewFiles.getAdapter() == null || listViewFiles.getAdapter().getCount() == 0) {
                                layoutFileActions.setVisibility(View.GONE);
                                listViewFiles.setVisibility(View.GONE);
                            }
                        } else {
                            Toast.makeText(this, "Ошибка при удалении", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Нет", null)
                    .show();
        });

        buttonRenameFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Переименовать");
            final EditText input = new EditText(this);
            input.setText(selectedFile.getName());
            builder.setView(input);
            builder.setPositiveButton("OK", (d, w) -> {
                String newName = input.getText().toString().trim();
                if (newName.isEmpty() || !newName.toLowerCase().endsWith(".csv")) {
                    Toast.makeText(this, "Введите имя с .csv", Toast.LENGTH_SHORT).show();
                    return;
                }
                File newFile = new File(csvDir, newName);
                if (newFile.exists()) {
                    Toast.makeText(this, "Файл уже существует", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selectedFile.renameTo(newFile)) {
                    Toast.makeText(this, "Переименован", Toast.LENGTH_SHORT).show();
                    selectedFile = newFile;
                    showFilesList();
                } else {
                    Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Отмена", null);
            builder.show();
        });

        buttonShareFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", selectedFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Отправить"));
        });

        setVersionName(); // ✅ Устанавливаем версию
        copySamplesFromAssets();
    }

    // ✅ Метод для отображения версии
    private void setVersionName() {
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            textViewVersion.setText("Версия: v" + versionName);
        } catch (Exception e) {
            textViewVersion.setText("Версия: v1.0.1");
        }
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String[] mimeTypes = {"text/plain", "text/csv", "application/csv", "application/vnd.ms-excel", "*/*"};
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

                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "imported_file.csv";
                }

                File outputFile = getUniqueFileForSave(fileName);
                if (outputFile == null) {
                    final String finalFileName = fileName;
                    mainHandler.post(() ->
                            Toast.makeText(this, "Файл уже добавлен: " + finalFileName, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }

                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outputFile))) {
                    for (String[] row : records) {
                        writer.write(String.join(";", row) + "\n");
                    }
                }

                mainHandler.post(() -> {
                    Toast.makeText(this, "Файл добавлен: " + outputFile.getName(), Toast.LENGTH_LONG).show();
                    showFilesList();
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() ->
                        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

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

    private File getUniqueFileForSave(String originalFileName) {
        if (!originalFileName.toLowerCase().endsWith(".csv")) {
            originalFileName += ".csv";
        }

        File targetFile = new File(csvDir, originalFileName);
        String baseName = originalFileName.substring(0, originalFileName.length() - 4);
        int counter = 1;

        List<String> newContent = getNormalizedContent(lastPickedUri);
        if (newContent == null) return targetFile;

        while (targetFile.exists()) {
            List<String> existingContent = getFileContent(targetFile);
            if (existingContent != null && isContentEqual(existingContent, newContent)) {
                return null;
            }
            targetFile = new File(csvDir, baseName + "_" + counter + ".csv");
            counter++;
        }

        return targetFile;
    }

    private List<String> getNormalizedContent(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            List<String[]> records = parseInputCSV(is);
            List<String> content = new ArrayList<>();
            for (String[] row : records) {
                content.add(String.join(";", row));
            }
            return content;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private List<String> getFileContent(File file) {
        List<String> content = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.add(line.trim());
            }
        } catch (IOException e) {
            return null;
        }
        return content;
    }

    private boolean isContentEqual(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                return false;
            }
        }
        return true;
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
                    tel = parts[0].trim(); email = parts[1].trim(); tgId = parts[2].trim(); name = parts[3].trim();
                } else if (line.contains("tg") || parts[0].startsWith("@")) {
                    tgId = parts[0].trim(); email = parts[1].trim(); name = parts[2].trim(); tel = parts[3].trim();
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
            String finalText = results.isEmpty() ?
                    "Ничего не найдено: " + query :
                    String.join("\n", results);
            runOnUiThread(() -> textViewResults.setText(finalText));
        }).start();
    }

    private void showFilesList() {
        File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "Нет файлов", Toast.LENGTH_SHORT).show();
            listViewFiles.setAdapter(null);
            listViewFiles.setVisibility(View.GONE);
            layoutFileActions.setVisibility(View.GONE);
            selectedFile = null;
            return;
        }

        List<String> fileNames = new ArrayList<>();
        for (File f : files) fileNames.add(f.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileNames);
        listViewFiles.setAdapter(adapter);
        listViewFiles.setVisibility(View.VISIBLE);
        layoutFileActions.setVisibility(View.GONE);
        listViewFiles.setOnItemClickListener((p, v, pos, id) -> {
            selectedFile = files[pos];
            layoutFileActions.setVisibility(View.VISIBLE);
        });
        selectedFile = null;
    }

    private void copySamplesFromAssets() {
        // Опционально
    }
}