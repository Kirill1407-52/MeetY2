package com.kirill.meetyou.service;

import com.kirill.meetyou.exception.ResourceNotFoundException;
import com.kirill.meetyou.model.Photo;
import com.kirill.meetyou.model.User;
import com.kirill.meetyou.repository.PhotoRepository;
import com.kirill.meetyou.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {
    private static final String CLEAR_MAIN_PHOTOS_LOG = "Очистка текущих главных фотографий для пользователя {}";
    private static final String IMAGES_DIR = "/home/kirill/Изображения/";
    private static final String FALSE_STRING = "false";
    private static final String TRUE_STRING = "true";

    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;

    @Transactional
    public Photo addPhoto(Long userId, MultipartFile file, String isMain) {
        try {
            validateUserId(userId);
            validateFile(file);
            validateIsMainParameter(isMain);

            User user = getUserById(userId);
            String fileName = saveFile(file);
            String photoUrl = IMAGES_DIR + fileName;

            Photo photo = createPhotoEntity(photoUrl, isMain, user);

            if (isMainPhoto(photo)) {
                log.debug(CLEAR_MAIN_PHOTOS_LOG, userId);
                photoRepository.clearMainPhotos(userId);
            }

            Photo savedPhoto = photoRepository.save(photo);
            log.info("Фотография успешно добавлена для пользователя {}", userId);
            return savedPhoto;
        } catch (IOException e) {
            handleFileSaveError(userId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось сохранить файл: " + e.getMessage());
        }
    }

    public List<Photo> getAllUserPhotos(Long userId) {
        validateUserId(userId);
        log.debug("Получение всех фотографий для пользователя {}", userId);

        List<Photo> photos = photoRepository.findByUserId(userId);
        if (photos.isEmpty()) {
            log.info("Фотографии для пользователя {} не найдены", userId);
        }
        return photos;
    }

    public Photo getPhotoById(Long userId, Long photoId) {
        validateUserId(userId);
        validatePhotoId(photoId);
        log.debug("Получение фотографии {} для пользователя {}", photoId, userId);

        return photoRepository.findByIdAndUserId(photoId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("Фотография с id: %d для пользователя с id: %d не найдена",
                                photoId, userId)));
    }

    @Transactional
    public Photo updatePhoto(Long userId, Long photoId, Photo photoDetails) {
        validateUserId(userId);
        validatePhotoId(photoId);
        validatePhoto(photoDetails);

        Photo photo = getPhotoById(userId, photoId);

        if (isMainPhoto(photoDetails) && !isMainPhoto(photo)) {
            log.debug(CLEAR_MAIN_PHOTOS_LOG, userId);
            photoRepository.clearMainPhotos(userId);
        }

        updatePhotoFields(photo, photoDetails);
        Photo updatedPhoto = photoRepository.save(photo);
        log.info("Фотография {} успешно обновлена для пользователя {}", photoId, userId);
        return updatedPhoto;
    }

    @Transactional
    public void deletePhoto(Long userId, Long photoId) {
        validateUserId(userId);
        validatePhotoId(photoId);

        Photo photo = getPhotoById(userId, photoId);
        deletePhotoFile(photo.getPhotoUrl());

        photoRepository.delete(photo);

        if (isMainPhoto(photo)) {
            log.debug("Удалена основная фотография для пользователя {}", userId);
        }

        log.info("Фотография {} успешно удалена для пользователя {}", photoId, userId);
    }

    @Transactional
    public List<Photo> addMultiplePhotos(Long userId, List<MultipartFile> files, String isMain) {
        validateUserId(userId);
        if (files == null || files.isEmpty()) {
            log.debug("Передан пустой список файлов для пользователя {}", userId);
            return Collections.emptyList();
        }

        validateIsMainParameter(isMain);

        boolean hasMainPhoto = TRUE_STRING.equals(isMain);
        if (hasMainPhoto) {
            log.debug(CLEAR_MAIN_PHOTOS_LOG, userId);
            photoRepository.clearMainPhotos(userId);
        }

        List<Photo> photos = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        processFiles(userId, files, hasMainPhoto, photos, failedFiles);

        return handlePhotoSaveResults(userId, photos, failedFiles);
    }

    @Transactional
    public Photo setPhotoAsMain(Long userId, Long photoId) {
        validateUserId(userId);
        validatePhotoId(photoId);

        photoRepository.clearMainPhotos(userId);
        Photo photo = getPhotoById(userId, photoId);
        photo.setIsMainString(TRUE_STRING);

        Photo savedPhoto = photoRepository.save(photo);
        log.info("Фотография {} установлена как главная для пользователя {}", photoId, userId);
        return savedPhoto;
    }

    private String saveFile(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must have an extension");
        }

        String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
        if (!fileExtension.matches("(?i)\\.(jpg|jpeg|png|gif|bmp)$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file extension");
        }

        String fileName = UUID.randomUUID() + fileExtension;

        Path uploadPath = Paths.get(IMAGES_DIR).normalize().toAbsolutePath();

        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        Path filePath = uploadPath.resolve(fileName).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }

        Files.write(filePath, file.getBytes());
        return fileName;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Передан пустой или null файл");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не может быть пустым или null");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            log.warn("Недопустимый тип файла: {}", contentType);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл должен быть изображением");
        }
    }

    private void validateIsMainParameter(String isMain) {
        if (isMain != null && !isMain.equals(TRUE_STRING) && !isMain.equals(FALSE_STRING)) {
            log.warn("Некорректное значение isMain: {}", isMain);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isMain должен быть 'true' или 'false'");
        }
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("Пользователь с id: %d не найден", userId)));
    }

    private Photo createPhotoEntity(String photoUrl, String isMain, User user) {
        Photo photo = new Photo();
        photo.setPhotoUrl(photoUrl);
        photo.setIsMainString(TRUE_STRING.equals(isMain) ? TRUE_STRING : FALSE_STRING);
        photo.setUploadDate(LocalDate.now());
        photo.setUser(user);
        return photo;
    }

    private void handleFileSaveError(Long userId, IOException e) {
        log.error("Ошибка при сохранении файла для пользователя {}: {}", userId, e.getMessage(), e);
    }

    private void updatePhotoFields(Photo photo, Photo photoDetails) {
        if (photoDetails.getPhotoUrl() != null && !photoDetails.getPhotoUrl().trim().isEmpty()) {
            photo.setPhotoUrl(photoDetails.getPhotoUrl());
        }

        if (photoDetails.getIsMainString() != null) {
            photo.setIsMainString(photoDetails.getIsMainString());
        }

        if (photoDetails.getUploadDate() != null) {
            photo.setUploadDate(photoDetails.getUploadDate());
        }
    }

    private void deletePhotoFile(String photoUrl) {
        if (photoUrl == null || photoUrl.isEmpty()) {
            return;
        }

        try {
            Path filePath = Paths.get(photoUrl);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Файл {} успешно удален из хранилища", photoUrl);
            } else {
                log.warn("Файл {} не найден в хранилище", photoUrl);
            }
        } catch (IOException e) {
            log.error("Ошибка при удалении файла {}: {}", photoUrl, e.getMessage(), e);
        }
    }

    private void processFiles(Long userId, List<MultipartFile> files, boolean hasMainPhoto,
                              List<Photo> photos, List<String> failedFiles) {
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                validateFile(file);
                String fileName = saveFile(file);
                String photoUrl = IMAGES_DIR + fileName;

                Photo photo = createPhotoEntity(photoUrl,
                        hasMainPhoto && i == 0 ? TRUE_STRING : FALSE_STRING,
                        getUserById(userId));
                photos.add(photo);
            } catch (IOException | ResponseStatusException e) {
                handleFileProcessingError(userId, file, failedFiles, e);
            }
        }
    }

    private void handleFileProcessingError(Long userId, MultipartFile file,
                                           List<String> failedFiles, Exception e) {
        String errorMessage = e instanceof IOException ?
                "Ошибка при сохранении файла {} для пользователя {}: {}" :
                "Некорректный файл {} для пользователя {}: {}";

        log.error(errorMessage, file.getOriginalFilename(), userId, e.getMessage());
        failedFiles.add(file.getOriginalFilename());
    }

    private List<Photo> handlePhotoSaveResults(Long userId, List<Photo> photos, List<String> failedFiles) {
        if (!photos.isEmpty()) {
            List<Photo> savedPhotos = photoRepository.saveAll(photos);
            log.info("Успешно добавлено {} фотографий для пользователя {}", savedPhotos.size(), userId);

            if (!failedFiles.isEmpty()) {
                log.warn("Не удалось сохранить файлы: {}", failedFiles);
                throw new ResponseStatusException(HttpStatus.PARTIAL_CONTENT,
                        "Частично добавлены фотографии, не удалось сохранить: " + failedFiles);
            }
            return savedPhotos;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Не удалось сохранить ни один файл: " + failedFiles);
        }
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            log.warn("Некорректный ID пользователя: {}", userId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ID пользователя");
        }
    }

    private void validatePhotoId(Long photoId) {
        if (photoId == null || photoId <= 0) {
            log.warn("Некорректный ID фотографии: {}", photoId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ID фотографии");
        }
    }

    private void validatePhoto(Photo photo) {
        if (photo == null) {
            log.warn("Передана null фотография");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Объект фотографии не может быть null");
        }
        if (photo.getPhotoUrl() == null || photo.getPhotoUrl().trim().isEmpty()) {
            log.warn("Пустой или null URL фотографии");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL фотографии не может быть пустым или null");
        }
    }

    private boolean isMainPhoto(Photo photo) {
        return TRUE_STRING.equals(photo.getIsMainString());
    }
}