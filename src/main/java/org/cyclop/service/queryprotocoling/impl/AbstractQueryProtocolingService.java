package org.cyclop.service.queryprotocoling.impl;

import net.jcip.annotations.NotThreadSafe;
import org.cyclop.model.UserIdentifier;
import org.cyclop.service.common.FileStorage;
import org.cyclop.service.queryprotocoling.QueryProtocolingService;
import org.cyclop.service.um.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;
import java.util.concurrent.atomic.AtomicReference;

/** @author Maciej Miklas */
@NotThreadSafe
@Named
@Validated
abstract class AbstractQueryProtocolingService<H> implements QueryProtocolingService<H> {

	private final static Logger LOG = LoggerFactory.getLogger(AbstractQueryProtocolingService.class);

	@Inject
	private UserManager um;

	@Inject
	private FileStorage storage;

	@Inject
	private AsyncFileStore<H> asyncFileStore;

	private UserIdentifier identifier;

	private final AtomicReference<H> history = new AtomicReference<>();

	protected abstract Class<H> getClazz();

	protected abstract H createEmpty();

	protected UserIdentifier getUser() {
		UserIdentifier fromCookie = um.readIdentifier();
		if (identifier == null) {
			LOG.debug("Using identifier from cookie: {}", fromCookie);
			identifier = fromCookie;
		} else {

			// make sure that there is only one user identifier pro http session
			// if it's not the case overwrite new one with existing one
			if (!fromCookie.equals(identifier)) {
				LOG.debug("Replacing {} with {}", fromCookie, identifier);
				um.registerIdentifier(identifier);
			}
		}
		return identifier;
	}

	// TODO add validation test
	@Override
	public void store(@NotNull H newHistory) {
		history.set(newHistory);
		UserIdentifier user = getUser();

		// this synchronization ensures that history will be not added to write
		// queue while it's being read from
		// disk in #readHistory()
		synchronized (asyncFileStore) {
			asyncFileStore.store(user, newHistory);
		}
	}

	// TODO validation does not work on abstract methods
	// TODO remove break line
	@Override
	public
	@NotNull
	H read() {
		if (history.get() == null) {
			synchronized (asyncFileStore) {
				if (history.get() == null) {
					UserIdentifier user = getUser();

					// history can be in write queue when user closes http
					// session and opens new few seconds later.
					// in this case async queue might be not flushed to disk yet
					H read = asyncFileStore.getFromWriteQueue(user);
					if (read == null) {
						read = storage.read(user, getClazz());
					}
					if (read == null) {
						read = createEmpty();
					}
					history.set(read);
				}
			}
		}
		return history.get();
	}

	@Override
	public boolean supported() {
		return storage.supported();
	}
}
