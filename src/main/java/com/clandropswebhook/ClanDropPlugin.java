
package com.clandropswebhook;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.*;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static net.runelite.client.RuneLite.SCREENSHOT_DIR;

@Slf4j
@PluginDescriptor(
	name = "Discord Clan Drop",
	description = "Posts clan notification drops into the discord",
	enabledByDefault = false
)
/**
 * Some screenshot code is a modified version from:
 * https://github.com/ATremonte/Discord-Level-Notifications/
 */
public class ClanDropPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ClanDropConfig config;

	final String subDir = "clanDropNotifications";
	final String fileName = "drop";
	private final Gson gson = new Gson();

	private static final DateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

	private boolean isStretchedEnabled = false;

	/**
	 * Check if the incoming message should be processed
	 * @param chatMessage
	 */
	@Subscribe(priority = 1)
	public void onChatMessage(ChatMessage chatMessage) {

		if(chatMessage.getType() != ChatMessageType.CLAN_MESSAGE) {
			return;
		}
		String messageContent = chatMessage.getMessage();

		if(!(messageContent.contains("received") || messageContent.contains("funny feeling") || messageContent.contains("sneaking into your backpack"))) {
			return;
		}

		String playerName = Text.sanitize(client.getLocalPlayer().getName());


		boolean isTarget = Text.sanitize(messageContent).contains(playerName);
		if(!isTarget) return;

		sendWebHook(messageContent);
	}

	/**
	 * Check if provided link is a valid webhook.
	 * @param event
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (event.getKey().equals("webhook")) {
			if(!validateWebHook()) {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Clan notification webhook is invalid or unreachable", null);
			}
		}
	}

	@Provides
    ClanDropConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanDropConfig.class);
	}

	/**
	 * Method used for handling the automatic date and text.
	 * Also decided if webhook should upload an image or just text.
	 * @param chatMessage The clan broadcast string to pass in the webhook.
	 */
	private void sendWebHook(String chatMessage) {

		validateWebHook();

		if(config.autoMessageEnabled()) {
			String msg = "";
			if(config.autoMessageDate()) {
				SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
				Date date = new Date();
				msg += formatter.format(date);
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "Drop Bot", msg + " " + config.autoMessage(), null);
		}

		if (config.screenshot()) {
			postRequestWebhookImage(chatMessage);
		} else {
			postRequestToWebHook(chatMessage);
		}
	}

	/**
	 * Build the webhook request and send it.
	 * Used for only text.
	 * @param message The clan broadcast string to pass in the webhook.
	 */
	private void postRequestToWebHook(String message) {
		DiscordWebHookBody discordWebHookBody = new DiscordWebHookBody();
		discordWebHookBody.setContent(message);

		HttpUrl url = HttpUrl.parse(config.webhook());
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload_json", gson.toJson(discordWebHookBody));


		buildRequestAndSend(url, requestBodyBuilder);
	}

	/**
	 * Method for validating the given webhook provided in the config by the user.
	 * @return
	 */
	private boolean validateWebHook() {
		if (config.webhook().isEmpty()) return false;
		if (!urlValidator(config.webhook())) return false;
		return urlResponseValidator(config.webhook());
	}

	/**
	 * Method for validating the URL of the webhook.
	 * @param url Input url from the config.
	 * @return return true if the url is valid. False otherwise.
	 */
	private boolean urlValidator(String url)
	{
		try {
			new URL(url).toURI();
			return true;
		}
		catch (Exception e) {
			log.error("Caught error: "+  e.getMessage());
			return false;
		}
	}

	/**
	 * Method for checking if the given URL is reachable.
	 * @param url Input url from the config.
	 * @return return true if the url is reachable. False otherwise.
	 */
	private boolean urlResponseValidator(String url) {
		try {
			URL validUrl = new URL(url);

			HttpsURLConnection con = (HttpsURLConnection) validUrl.openConnection();
			con.setRequestMethod("HEAD");

			int responseCode = con.getResponseCode();

			return responseCode == HttpsURLConnection.HTTP_OK;
		}
		catch (Exception e) {
			log.error("Caught error: "+  e.getMessage());
			return false;
		}
	}

	/**
	 * Method for webhook request using a picture.
	 * Takes a screenshot of the game. (Disables stretch mode in case screenshotOnlyChatbox is enabled
	 * in the config file. This is done in order to circumvent the scaling factor of stretch mode).
	 * @param msg
	 */
	private void postRequestWebhookImage(String msg) {
		if(config.onlyChat() && client.isStretchedEnabled()) {
			client.setStretchedEnabled(false);
			isStretchedEnabled = true;
		}
		Consumer<Image> imageCallback = (img) -> executor.submit(() -> takeScreenshot(fileName, subDir, img, msg));
		drawManager.requestNextFrameListener(imageCallback);
	}

	/**
	 * Crops the given game image if stretch is enabled
	 * @param fileName name of the file
	 * @param subDir name of the directory to save to
	 * @param image image object
	 * @param msg message to send with the webhook
	 */
	private void takeScreenshot(String fileName, String subDir, Image image, String msg)
	{

		int gameOffsetX = 0;
		int gameOffsetY = 0;

		if(config.onlyChat()) {
			BufferedImage screenshot = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			Graphics graphics = screenshot.getGraphics();

			Widget widget = client.getWidget(WidgetInfo.CHATBOX_PARENT);

			Rectangle bounds = widget.getBounds();

			// Draw the game onto the screenshot
			graphics.drawImage(image, 0,0,null);

			BufferedImage croppedImage = screenshot.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);

			if(isStretchedEnabled) {
				client.setStretchedEnabled(true);
			}

			takeScreenshot(croppedImage, fileName, subDir, msg);

		} else {
			BufferedImage screenshot = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);


			Graphics graphics = screenshot.getGraphics();
			// Draw the game onto the screenshot
			graphics.drawImage(image, gameOffsetX, gameOffsetY, null);
			takeScreenshot(screenshot, fileName, subDir, msg);
		}
	}

	/**
	 * Method for writing screenshot to disk.
	 * @param screenshot
	 * @param fileName
	 * @param subDir
	 * @param msg
	 */
	public void takeScreenshot(BufferedImage screenshot, String fileName, @Nullable String subDir, String msg)
	{

		File playerFolder;
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{

			String playerDir = client.getLocalPlayer().getName();

			if (!Strings.isNullOrEmpty(subDir))
			{
				playerDir += File.separator + subDir;
			}

			playerFolder = new File(SCREENSHOT_DIR, playerDir);
		}
		else
		{
			playerFolder = SCREENSHOT_DIR;
		}

		playerFolder.mkdirs();

		fileName += (fileName.isEmpty() ? "" : " ") + format(new Date());

		try
		{
			File screenshotFile = new File(playerFolder, fileName + ".png");

			// To make sure that screenshots don't get overwritten, check if file exists,
			// and if it does create file with same name and suffix.
			int i = 1;
			while (screenshotFile.exists())
			{
				screenshotFile = new File(playerFolder, fileName + String.format("(%d)", i++) + ".png");
			}

			ImageIO.write(screenshot, "PNG", screenshotFile);

			uploadScreenshot(screenshot, msg);
		}
		catch (IOException ex)
		{
			log.warn("error writing screenshot", ex);
		}
	}


	/**
	 * Method for building and sending the webhook to discord with an image and text
	 * @param screenshotFile
	 * @param msg
	 * @throws IOException
	 */
	private void uploadScreenshot(BufferedImage screenshotFile, String msg) throws IOException
	{

		DiscordWebHookBody discordWebHookBody = new DiscordWebHookBody();
		discordWebHookBody.setContent(msg);

		HttpUrl url = HttpUrl.parse(config.webhook());
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload_json", gson.toJson(discordWebHookBody));

		byte[] imageBytes;
		try {
			imageBytes = convertImageToByteArray(screenshotFile);
		} catch (IOException e) {
			log.warn("Error converting image to byte array", e);
			return;
		}

		requestBodyBuilder.addFormDataPart("file", "image.png",
				RequestBody.create(MediaType.parse("image/png"), imageBytes));
		buildRequestAndSend(url, requestBodyBuilder);
	}

	private void buildRequestAndSend(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		RequestBody requestBody = requestBodyBuilder.build();
		Request request = new Request.Builder()
				.url(url)
				.post(requestBody)
				.build();
		sendRequest(request);
	}
	private void sendRequest(Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}


	private static String format(Date date)
	{
		synchronized (TIME_FORMAT)
		{
			return TIME_FORMAT.format(date);
		}
	}
	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}
}