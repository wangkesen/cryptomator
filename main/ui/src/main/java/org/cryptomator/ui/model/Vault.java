/*******************************************************************************
 * Copyright (c) 2016, 2017 Sebastian Stenzel and others.
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.ui.model;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.common.LazyInitializer;
import org.cryptomator.common.Optionals;
import org.cryptomator.common.settings.Settings;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.cryptofs.CryptoFileSystem;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProperties.FileSystemFlags;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.api.CryptoException;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.frontend.webdav.ServerLifecycleException;
import org.cryptomator.frontend.webdav.WebDavServer;
import org.cryptomator.frontend.webdav.mount.MountParams;
import org.cryptomator.frontend.webdav.mount.Mounter.CommandFailedException;
import org.cryptomator.frontend.webdav.mount.Mounter.Mount;
import org.cryptomator.frontend.webdav.mount.Mounter.UnmountOperation;
import org.cryptomator.frontend.webdav.servlet.WebDavServletController;
import org.cryptomator.ui.model.VaultModule.PerVault;
import org.fxmisc.easybind.EasyBind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Binding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

@PerVault
public class Vault {

	private static final Logger LOG = LoggerFactory.getLogger(Vault.class);
	private static final String MASTERKEY_FILENAME = "masterkey.cryptomator";

	private final Settings settings;
	private final VaultSettings vaultSettings;
	private final WebDavServer server;
	private final BooleanProperty unlocked = new SimpleBooleanProperty();
	private final BooleanProperty mounted = new SimpleBooleanProperty();
	private final AtomicReference<CryptoFileSystem> cryptoFileSystem = new AtomicReference<>();

	private WebDavServletController servlet;
	private Mount mount;

	@Inject
	Vault(Settings settings, VaultSettings vaultSettings, WebDavServer server) {
		this.settings = settings;
		this.vaultSettings = vaultSettings;
		this.server = server;
	}

	// ******************************************************************************
	// Commands
	// ********************************************************************************/

	private CryptoFileSystem getCryptoFileSystem(CharSequence passphrase) throws IOException, CryptoException {
		return LazyInitializer.initializeLazily(cryptoFileSystem, () -> unlockCryptoFileSystem(passphrase), IOException.class);
	}

	private CryptoFileSystem unlockCryptoFileSystem(CharSequence passphrase) throws IOException, CryptoException {
		CryptoFileSystemProperties fsProps = CryptoFileSystemProperties.cryptoFileSystemProperties() //
				.withPassphrase(passphrase) //
				.withFlags(EnumSet.noneOf(FileSystemFlags.class)) // TODO: use withFlags() with CryptoFS 1.3.1
				.withMasterkeyFilename(MASTERKEY_FILENAME) //
				.build();
		return CryptoFileSystemProvider.newFileSystem(getPath(), fsProps);
	}

	public void create(CharSequence passphrase) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(getPath())) {
			for (Path file : stream) {
				if (!file.getFileName().toString().startsWith(".")) {
					throw new DirectoryNotEmptyException(getPath().toString());
				}
			}
		}
		if (!isValidVaultDirectory()) {
			CryptoFileSystemProvider.initialize(getPath(), MASTERKEY_FILENAME, passphrase);
		} else {
			throw new FileAlreadyExistsException(getPath().toString());
		}
	}

	public void changePassphrase(CharSequence oldPassphrase, CharSequence newPassphrase) throws IOException, InvalidPassphraseException {
		CryptoFileSystemProvider.changePassphrase(getPath(), MASTERKEY_FILENAME, oldPassphrase, newPassphrase);
	}

	public synchronized void unlock(CharSequence passphrase) throws ServerLifecycleException {
		try {
			FileSystem fs = getCryptoFileSystem(passphrase);
			if (!server.isRunning()) {
				server.start();
			}
			servlet = server.createWebDavServlet(fs.getPath("/"), vaultSettings.getId() + "/" + vaultSettings.mountName().get());
			servlet.start();
			Platform.runLater(() -> {
				unlocked.set(true);
			});
		} catch (IOException e) {
			LOG.error("Unable to provide filesystem", e);
		}
	}

	public synchronized void mount() throws CommandFailedException {
		if (servlet == null) {
			throw new IllegalStateException("Mounting requires unlocked WebDAV servlet.");
		}

		MountParams mountParams = MountParams.create() //
				.withWindowsDriveLetter(vaultSettings.winDriveLetter().get()) //
				.withPreferredGvfsScheme(settings.preferredGvfsScheme().get()) //
				.build();

		mount = servlet.mount(mountParams);
		Platform.runLater(() -> {
			mounted.set(true);
		});
	}

	public synchronized void unmount() throws CommandFailedException {
		unmount(Function.identity());
	}

	public synchronized void unmountForced() throws CommandFailedException {
		unmount(Optionals.unwrap(Mount::forced));
	}

	private synchronized void unmount(Function<Mount, ? extends UnmountOperation> unmountOperationChooser) throws CommandFailedException {
		if (mount != null) {
			unmountOperationChooser.apply(mount).unmount();
		}
		Platform.runLater(() -> {
			mounted.set(false);
		});
	}

	public boolean supportsForcedUnmount() {
		return mount != null && mount.forced().isPresent();
	}

	public synchronized void lock() throws ServerLifecycleException, IOException {
		if (servlet != null) {
			servlet.stop();
		}
		CryptoFileSystem fs = cryptoFileSystem.getAndSet(null);
		if (fs != null) {
			fs.close();
		}
		Platform.runLater(() -> {
			unlocked.set(false);
		});
	}

	/**
	 * Ejects any mounted drives and locks this vault. no-op if this vault is currently locked.
	 */
	public void prepareForShutdown() {
		try {
			unmount();
		} catch (CommandFailedException e) {
			if (supportsForcedUnmount()) {
				try {
					unmountForced();
				} catch (CommandFailedException e1) {
					LOG.warn("Failed to force unmount vault.");
				}
			} else {
				LOG.warn("Failed to gracefully unmount vault.");
			}
		}
		try {
			lock();
		} catch (Exception e) {
			LOG.warn("Failed to lock vault.");
		}
	}

	public void reveal() throws CommandFailedException {
		if (mount != null) {
			mount.reveal();
		}
	}

	// ******************************************************************************
	// Getter/Setter
	// *******************************************************************************/

	public Observable[] observables() {
		return new Observable[] {unlocked, mounted};
	}

	public VaultSettings getVaultSettings() {
		return vaultSettings;
	}

	public synchronized String getWebDavUrl() {
		return servlet.getServletRootUri().toString();
	}

	public Path getPath() {
		return vaultSettings.path().getValue();
	}

	public Binding<String> displayablePath() {
		Path homeDir = Paths.get(SystemUtils.USER_HOME);
		return EasyBind.map(vaultSettings.path(), p -> {
			if (p.startsWith(homeDir)) {
				Path relativePath = homeDir.relativize(p);
				String homePrefix = SystemUtils.IS_OS_WINDOWS ? "~\\" : "~/";
				return homePrefix + relativePath.toString();
			} else {
				return p.toString();
			}
		});
	}

	/**
	 * @return Directory name without preceeding path components and file extension
	 */
	public Binding<String> name() {
		return EasyBind.map(vaultSettings.path(), Path::getFileName).map(Path::toString);
	}

	public boolean doesVaultDirectoryExist() {
		return Files.isDirectory(getPath());
	}

	public boolean isValidVaultDirectory() {
		return CryptoFileSystemProvider.containsVault(getPath(), MASTERKEY_FILENAME);
	}

	public BooleanProperty unlockedProperty() {
		return unlocked;
	}

	public BooleanProperty mountedProperty() {
		return mounted;
	}

	public boolean isUnlocked() {
		return unlocked.get();
	}

	public boolean isMounted() {
		return mounted.get();
	}

	public long pollBytesRead() {
		CryptoFileSystem fs = cryptoFileSystem.get();
		if (fs != null) {
			return fs.getStats().pollBytesRead();
		} else {
			return 0l;
		}
	}

	public long pollBytesWritten() {
		CryptoFileSystem fs = cryptoFileSystem.get();
		if (fs != null) {
			return fs.getStats().pollBytesWritten();
		} else {
			return 0l;
		}
	}

	public String getMountName() {
		return vaultSettings.mountName().get();
	}

	public void setMountName(String mountName) throws IllegalArgumentException {
		if (StringUtils.isBlank(mountName)) {
			throw new IllegalArgumentException("mount name is empty");
		} else {
			vaultSettings.mountName().set(VaultSettings.normalizeMountName(mountName));
		}
	}

	public Character getWinDriveLetter() {
		if (vaultSettings.winDriveLetter().get() == null) {
			return null;
		} else {
			return vaultSettings.winDriveLetter().get().charAt(0);
		}
	}

	public void setWinDriveLetter(Character winDriveLetter) {
		if (winDriveLetter == null) {
			vaultSettings.winDriveLetter().set(null);
		} else {
			vaultSettings.winDriveLetter().set(String.valueOf(winDriveLetter));
		}
	}

	public String getId() {
		return vaultSettings.getId();
	}

	// ******************************************************************************
	// Hashcode / Equals
	// *******************************************************************************/

	@Override
	public int hashCode() {
		return Objects.hash(vaultSettings);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Vault && obj.getClass().equals(this.getClass())) {
			final Vault other = (Vault) obj;
			return Objects.equals(this.vaultSettings, other.vaultSettings);
		} else {
			return false;
		}
	}

}