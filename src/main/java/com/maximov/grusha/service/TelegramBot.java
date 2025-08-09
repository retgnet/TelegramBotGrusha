package com.maximov.grusha.service;

import com.maximov.grusha.config.BotConfig;
import com.maximov.grusha.model.User;
import com.maximov.grusha.model.UserRepository;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;
    private final BotConfig config;
    private static final String ADMIN_ID_NIKOLAY = "5559129467";
    private static final long ADMIN_ID_MARYA = 810737452;
    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "Запуск бота"));
//listOfCommands.add(new BotCommand("/register", "Регистрация"));
        listOfCommands.add(new BotCommand("/notify_admin", "Отправить сообщение администратору"));

        try{
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        }catch (TelegramApiException e){
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
        if(update.hasMessage() && update.getMessage().hasText()){
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();


            switch (messageText){
                case "/start":
                    registerUser(update.getMessage());
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;
/*case "/register":
register(chatId);
break;*/
                case "/notify_admin":
                    handleNotifyAdminCommand(chatId);
                    break;
                default:
                    if (isWaitingForAdminMessage(chatId)) {
                        handleAdminMessage(chatId, messageText);
                    } else {
                        sendMessage(chatId, "Неизвестная команда");
                    }

            }

        } else if (update.hasCallbackQuery()) {
            String callBackData = update.getCallbackQuery().getData();
            long messageId = update.getCallbackQuery().getMessage().getMessageId();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if(callBackData.equals("YES_BUTTON")){
                String text = "Введите Имя и номер телефона";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try{
                    execute(message);
                }
                catch (TelegramApiException e){
                    log.error("Произошла ошибка {}", e.getMessage());
                }
            }
            else if (callBackData.equals("NO_BUTTON")) {
                String text = "Вы нажали \"Нет\" ";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try{
                    execute(message);
                }
                catch (TelegramApiException e){
                    log.error("Произошла ошибка {}", e.getMessage());
                }
            }

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

    // Добавим систему отслеживания ожидания сообщения
    private Set<Long> waitingForAdminMessage = new HashSet<>();

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
        sendMessage(chatId, "Введите текст сообщения для отправки администратору");
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
        log.info("Ответ пользователю {}", name);
        sendMessage(chatId, answer);

    }
    //чтобы для каждого сообщения определять свою клавиатуру нужно вынести по разным методам
    private void sendMessage(long chatId, String textToSend){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);
        if (isWaitingForAdminMessage(chatId)) {
            message.setReplyMarkup(null);
        } else {
            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true);
            List<KeyboardRow> keyboardRows = new ArrayList<>();
            KeyboardRow row = new KeyboardRow();
            row.add("Забронировать стол");
            keyboardRows.add(row);
            row = new KeyboardRow();
            row.add("Меню");
            row.add("Барная карта");
            keyboardRows.add(row);
            keyboardMarkup.setKeyboard(keyboardRows);
            message.setReplyMarkup(keyboardMarkup);
        }

        try{
            execute(message);
        }
        catch (TelegramApiException e){
            log.error("Произошла ошибка {}", e.getMessage());
        }

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

        try{
            execute(message);
        }
        catch (TelegramApiException e){
            log.error("Произошла ошибка {}", e.getMessage());
        }


    }
    private void sendMessageToUser(long userId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(userId));
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения: {}", e.getMessage());
        }
    }
    private void notifyAdmin(String message) {
//sendMessageToUser(Long.parseLong(String.valueOf(ADMIN_ID_MARYA)), message);
        sendMessageToUser(Long.parseLong(String.valueOf(ADMIN_ID_NIKOLAY)), message);
    }

}