package com.maximov.grusha.service;

import com.maximov.grusha.config.BotConfig;
import com.maximov.grusha.model.User;
import com.maximov.grusha.model.UserRepository;
import com.maximov.grusha.model.Booking;
import com.maximov.grusha.model.BookingRepository;
import com.vdurmont.emoji.EmojiParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    private static final int MAX_GUESTS = 20; // можно вынести в конфиг

    private enum BookingState {
        WAITING_FOR_NAME,
        WAITING_FOR_PHONE,
        WAITING_FOR_GUESTS, // НОВОЕ
        WAITING_FOR_DATE,
        WAITING_FOR_TIME
    }

    private static class BookingDraft {
        String name;
        String phone;
        Integer guests; // НОВОЕ
        java.time.LocalDate date;
        java.time.LocalTime time;
    }

    private Set<Long> waitingForAdminMessage = new HashSet<>();
    private Map<Long, BookingState> bookingStates = new HashMap<>();
    private Map<Long, BookingDraft> bookingDrafts = new ConcurrentHashMap<>();

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookingRepository bookingRepository;
    private final BotConfig config;


    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "Запуск бота"));
        listOfCommands.add(new BotCommand("/register", "Регистрация"));
        listOfCommands.add(new BotCommand("/notify_admin", "Отправить сообщение администратору"));
        listOfCommands.add(new BotCommand("/my_bookings", "Мои брони")); // НОВОЕ

        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Ошибка настройки команд бота {}", e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (isBooking(chatId)) {
                handleBookingMessage(chatId, messageText);
                return;
            }

            switch (messageText) {
                case "/start":
                    registerUser(update.getMessage());
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;
                case "/register":
                    register(chatId);
                    break;
                case "Забронировать стол":
                    handleBookTable(chatId);
                    break;
                case "/notify_admin":
                    handleNotifyAdminCommand(chatId);
                    break;
                case "/my_bookings":

                case "Мои брони": // если добавите кнопку
                    handleMyBookingsMenu(chatId);
                    break;
                default:
                    if (isWaitingForAdminMessage(chatId)) {
                        handleAdminMessage(chatId, messageText);
                    } else {
                        prepareAndSendMessage(chatId, "Неизвестная команда");
                    }
            }

        } else if (update.hasCallbackQuery()) {
            String callBackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            switch (callBackData) {
                case "BOOK_TABLE":
                    startBookingProcess(chatId, "now");
                    break;

                case "CANCEL_BOOKING":
                    // Раньше тут было cancelBooking(chatId);
                    cancelBookingDialog(chatId);
                    break;

                // Если реализуете меню "Мои брони"
                case "VIEW_MY_BOOKINGS":
                    handleViewMyBookings(chatId);
                    break;
                case "START_CANCEL_BOOKING":
                    handleCancelMyBookingStart(chatId);
                    break;
                case "BACK_MY_BOOKINGS_MENU":
                    handleMyBookingsMenu(chatId);
                    break;
                default:
                    if (callBackData.startsWith("CANCEL_BOOKING_ID_")) {
                        long bookingId = Long.parseLong(callBackData.substring("CANCEL_BOOKING_ID_".length()));
                        handleCancelBookingById(chatId, bookingId);
                    }
                    break;
            }
        }
    }

    private void handleMyBookingsMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите действие:");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка: посмотреть мои брони
        InlineKeyboardButton viewBtn = new InlineKeyboardButton();
        viewBtn.setText("Посмотреть мои брони");
        viewBtn.setCallbackData("VIEW_MY_BOOKINGS");

        // Кнопка: отменить мою бронь
        InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
        cancelBtn.setText("Отменить мою бронь");
        cancelBtn.setCallbackData("START_CANCEL_BOOKING");

        rows.add(Collections.singletonList(viewBtn));
        rows.add(Collections.singletonList(cancelBtn));

        keyboard.setKeyboard(rows);
        message.setReplyMarkup(keyboard);

        executeMessage(message);
    }
    private void handleViewMyBookings(long chatId) {
        List<Booking> bookings = bookingRepository.findByChatIdOrderByBookingDateTimeDesc(chatId);
        if (bookings.isEmpty()) {
            sendMessage(chatId, "У вас пока нет бронирований.");
            return;
        }
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        StringBuilder sb = new StringBuilder("Ваши бронирования:\n\n");
        for (Booking b : bookings) {
            sb.append("• ")
                    .append(b.getBookingDateTime().format(df))
                    .append(", гостей: ").append(b.getGuests() == null ? "-" : b.getGuests())
                    .append(" — ").append(b.getName())
                    .append(", ").append(b.getPhone())
                    .append(" [").append(b.getStatus()).append("]\n");
        }
        sendMessage(chatId, sb.toString());
    }
    private void handleCancelMyBookingStart(long chatId) {
        List<Booking> active = bookingRepository.findByChatIdAndStatusOrderByBookingDateTimeDesc(chatId, "ACTIVE");
        if (active.isEmpty()) {
            sendMessage(chatId, "У вас нет активных броней для отмены.");
            return;
        }

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите бронь для отмены:");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

        for (Booking b : active) {
            String text = b.getBookingDateTime().format(df) +
                    ", гостей: " + (b.getGuests() == null ? "-" : b.getGuests());
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(text);
            btn.setCallbackData("CANCEL_BOOKING_ID_" + b.getId());

            rows.add(Collections.singletonList(btn));
        }

        // Кнопка "Назад"
        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("← Назад");
        back.setCallbackData("BACK_MY_BOOKINGS_MENU");
        rows.add(Collections.singletonList(back));

        keyboard.setKeyboard(rows);
        message.setReplyMarkup(keyboard);

        executeMessage(message);
    }
    private void handleCancelBookingById(long chatId, long bookingId) {
        Optional<Booking> opt = bookingRepository.findById(bookingId);
        if (opt.isEmpty()) {
            sendMessage(chatId, "Бронь не найдена или уже отменена.");
            return;
        }
        Booking booking = opt.get();

        // Безопасность: пользователь может отменять только свои брони
        if (!Objects.equals(booking.getChatId(), chatId)) {
            sendMessage(chatId, "Вы не можете отменить эту бронь.");
            return;
        }
        if (!"ACTIVE".equals(booking.getStatus())) {
            sendMessage(chatId, "Эта бронь уже не активна.");
            return;
        }

        booking.setStatus("CANCELLED");
        bookingRepository.save(booking);

        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        sendMessage(chatId, "Бронь отменена:\n" +
                booking.getBookingDateTime().format(df) +
                ", гостей: " + (booking.getGuests() == null ? "-" : booking.getGuests()));

        notifyAdmin("Отмена бронирования:\n" +
                "Пользователь ID: " + chatId + "\n" +
                "Дата/время: " + booking.getBookingDateTime().format(df) + "\n" +
                "Гостей: " + (booking.getGuests() == null ? "-" : booking.getGuests()));
    }






    private void handleBookingMessage(long chatId, String messageText) {
        BookingState currentState = bookingStates.get(chatId);
        BookingDraft draft = bookingDrafts.computeIfAbsent(chatId, id -> new BookingDraft());

        switch (currentState) {
            case WAITING_FOR_NAME:
                draft.name = messageText.trim();
                if (draft.name.isEmpty()) {
                    sendMessage(chatId, "Имя не может быть пустым. Введите ваше имя:");
                    return;
                }
                bookingStates.put(chatId, BookingState.WAITING_FOR_PHONE);
                sendMessage(chatId, "Введите ваш номер телефона:");
                break;

            case WAITING_FOR_PHONE:
                draft.phone = messageText.trim();
                if (draft.phone.isEmpty()) {
                    sendMessage(chatId, "Телефон не может быть пустым. Введите номер телефона:");
                    return;
                }
                bookingStates.put(chatId, BookingState.WAITING_FOR_GUESTS);
                sendMessage(chatId, "Введите количество гостей (1–" + MAX_GUESTS + "):");
                break;

            case WAITING_FOR_GUESTS:
                Integer guests = parseGuests(messageText);
                if (guests == null) {
                    sendMessage(chatId, "Неверное число. Введите количество гостей (1–" + MAX_GUESTS + "):");
                    return;
                }
                draft.guests = guests;
                bookingStates.put(chatId, BookingState.WAITING_FOR_DATE);
                sendMessage(chatId, "Введите дату бронирования в формате, (например, 09-08-2025):");
                break;

            case WAITING_FOR_DATE:
                java.time.LocalDate date = parseDate(messageText);
                if (date == null) {
                    sendMessage(chatId, "Неверный формат даты. Используйте, например 09-08-2025:");
                    return;
                }
                if (date.isBefore(java.time.LocalDate.now())) {
                    sendMessage(chatId, "Дата уже прошла. Введите будущую дату:");
                    return;
                }
                draft.date = date;
                bookingStates.put(chatId, BookingState.WAITING_FOR_TIME);
                sendMessage(chatId, "Введите время бронирования в формате, (например, 19:30):");
                break;


            case WAITING_FOR_TIME:
                java.time.LocalTime time = parseTime(messageText);
                if (time == null) {
                    sendMessage(chatId, "Неверный формат времени. Используйте ЧЧ:ММ, например 19:30:");
                    return;
                }
                java.time.LocalDateTime dateTime = java.time.LocalDateTime.of(draft.date, time);
                if (dateTime.isBefore(java.time.LocalDateTime.now())) {
                    sendMessage(chatId, "Вы выбрали прошедшее время. Введите время в будущем");
                    return;
                }
                draft.time = time;
                processBooking(chatId);
                break;

        }
    }

    private Integer parseGuests(String s) {
        try {
            int g = Integer.parseInt(s.trim());
            if (g >= 1 && g <= MAX_GUESTS) return g;
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private java.time.LocalDate parseDate(String s) {
        try {
            return java.time.LocalDate.parse(s.trim(), Constants.getDATE_INPUT());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void handleAdminMessage(long chatId, String messageText) {
        if (!messageText.isEmpty()) {
            notifyAdmin("Новое сообщение от пользователя " + chatId + ":\n" + messageText);
            sendMessage(chatId, "Сообщение успешно отправлено администратору");
// Убираем статус ожидания сообщения
            removeWaitingForAdminMessage(chatId);
        } else {
            sendMessage(chatId, "Пожалуйста, введите текст сообщения");
        }
    }

    private void startBookingProcess(long chatId, String type) {
        bookingStates.put(chatId, BookingState.WAITING_FOR_NAME);
        bookingDrafts.put(chatId, new BookingDraft());

        String messageText = (type.equals("now"))
                ? "Введите ваше имя для бронирования стола:"
                : "Выберите дату и время бронирования";

        sendMessage(chatId, messageText);
    }

    private void processBooking(long chatId) {
        BookingDraft draft = bookingDrafts.get(chatId);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.of(draft.date, draft.time);

        Booking booking = new Booking();
        booking.setChatId(chatId);
        booking.setName(draft.name);
        booking.setPhone(draft.phone);
        booking.setGuests(draft.guests); // НОВОЕ
        booking.setBookingDateTime(dateTime);
        booking.setStatus("ACTIVE");
        bookingRepository.save(booking);

        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        sendMessage(chatId, "Ваша бронь принята!\n" +
                "Имя: " + draft.name + "\n" +
                "Телефон: " + draft.phone + "\n" +
                "Гостей: " + draft.guests + "\n" + // НОВОЕ
                "Дата и время: " + dateTime.format(df));

        notifyAdmin("Новое бронирование:\n" +
                "Имя: " + draft.name + "\n" +
                "Телефон: " + draft.phone + "\n" +
                "Гостей: " + draft.guests + "\n" + // НОВОЕ
                "Дата и время: " + dateTime.format(df) + "\n" +
                "ID: " + chatId);

        clearBookingState(chatId);
    }

    private void handleMyBookings(long chatId) {
        List<Booking> bookings = bookingRepository.findByChatIdOrderByBookingDateTimeDesc(chatId);
        if (bookings.isEmpty()) {
            sendMessage(chatId, "У вас пока нет броней.");
            return;
        }
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        StringBuilder sb = new StringBuilder("Ваши бронирования:\n\n");
        for (Booking b : bookings) {
            sb.append("• ")
                    .append(b.getBookingDateTime().format(df))
                    .append(" — ").append(b.getName())
                    .append(", ").append(b.getPhone())
                    .append(", гостей: ").append(b.getGuests() == null ? "-" : b.getGuests())
                    .append(" [").append(b.getStatus()).append("]\n");
        }
        sendMessage(chatId, sb.toString());
    }

    private void cancelBookingDialog(long chatId) {
        if (isBooking(chatId)) {
            clearBookingState(chatId); // вы уже используете этот метод для очистки состояний
            sendMessage(chatId, "Процесс бронирования отменён.");
        } else {
            sendMessage(chatId, "У вас нет активного процесса бронирования.");
        }
    }


    private boolean isWaitingForAdminMessage(long chatId) {
        return waitingForAdminMessage.contains(chatId);
    }

    private void setWaitingForAdminMessage(long chatId) {
        waitingForAdminMessage.add(chatId);
    }

    private void removeWaitingForAdminMessage(long chatId) {
        waitingForAdminMessage.remove(chatId);
    }

    // Модифицируем обработку /notify_admin
    private void handleNotifyAdminCommand(long chatId) {
        setWaitingForAdminMessage(chatId);
        prepareAndSendMessage(chatId, "Введите текст сообщения для отправки администратору");
    }
    private void registerUser(Message msg) {
        if(userRepository.findById(msg.getChatId()).isEmpty()){
            var chatId = msg.getChatId();
            var chat = msg.getChat();
            User user = new User();
            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));
            userRepository.save(user);
// Уведомляем администратора о новой регистрации
            notifyAdmin("Новый пользователь зарегистрирован:\n" +
                    "Имя: " + chat.getFirstName() + "\n" +
                    "ID: " + chatId);
        }
    }
    private void startCommandReceived(long chatId, String name){
        String answer = EmojiParser.parseToUnicode("Привет, " + "добро пожаловать в телеграмм бота Grusha!" + "\uD83D\uDD25");
        sendMessage(chatId, answer);

    }
    private void sendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);

        if (isWaitingForAdminMessage(chatId) || isBooking(chatId)) {
            message.setReplyMarkup(null);
        } else {
            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true);
            List<KeyboardRow> keyboardRows = new ArrayList<>();

            KeyboardRow row = new KeyboardRow();
            row.add("Забронировать стол");
            keyboardRows.add(row);

            row = new KeyboardRow();
            //row.add("Мои брони");
            //row.add("Меню");
            //row.add("Барная карта");
            keyboardRows.add(row);

            keyboardMarkup.setKeyboard(keyboardRows);
            message.setReplyMarkup(keyboardMarkup);
        }

        executeMessage(message);
    }
    private boolean isBooking(long chatId) {
        return bookingStates.containsKey(chatId);
    }
    private void handleBookTable(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите действие для бронирования стола:");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();


        List<InlineKeyboardButton> row1 = new ArrayList<>();

        InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
        inlineKeyboardButton.setText("Забронировать стол");
        inlineKeyboardButton.setCallbackData("BOOK_TABLE");
        row1.add(inlineKeyboardButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        inlineKeyboardButton = new InlineKeyboardButton();
        inlineKeyboardButton.setText("Отмена");
        inlineKeyboardButton.setCallbackData("CANCEL_BOOKING");
        row2.add(inlineKeyboardButton);

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        message.setReplyMarkup(keyboard);

        executeMessage(message);
    }

    private void clearBookingState(long chatId) {
        bookingStates.remove(chatId);
        bookingDrafts.remove(chatId);
    }

    public void register(Long chatId){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Хотите зарегистрироваться?");
        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();
        var yesButton = new InlineKeyboardButton();
        yesButton.setText("Да");
        yesButton.setCallbackData("YES_BUTTON");

        var noButtom = new InlineKeyboardButton();
        noButtom.setText("Нет");
        noButtom.setCallbackData("NO_BUTTON");

        rowInLine.add(yesButton);
        rowInLine.add(noButtom);
        rowsInLine.add(rowInLine);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);

        executeMessage(message);

    }
    private void sendMessageToUser(long userId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(userId));
        message.setText(text);

        executeMessage(message);
    }
    private void notifyAdmin(String message) {
        sendMessageToUser(Long.parseLong(String.valueOf(Constants.getADMIN_ID_NIKOLAY())), message);
        sendMessageToUser(Long.parseLong(String.valueOf(Constants.getADMIN_ID_MARYA())), message);
    }
    private void executeMessage(SendMessage message){
        try{
            execute(message);
        }
        catch (TelegramApiException e){
            log.error("Произошла ошибка {}", e.getMessage());
        }
    }
    private void prepareAndSendMessage(long chatId, String textToSend){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);
        executeMessage(message);
    }

}