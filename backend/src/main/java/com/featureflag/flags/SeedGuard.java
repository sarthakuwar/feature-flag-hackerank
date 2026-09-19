package com.featureflag.flags;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;

/**
 * Decides whether {@link FlagSeeder} may wipe and reseed the database it is connected to. Seeding
 * deletes six collections on every start, so it only runs against this machine (localhost,
 * 127.0.0.1 or [::1]) unless FEATUREFLAG_SEED=true says the operator really means it.
 */
@Component
public class SeedGuard {

	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

	private final String mongoUri;

	private final boolean forced;

	public SeedGuard(@Value("${spring.data.mongodb.uri:mongodb://localhost:27017/featureflag_db}") String mongoUri,
			@Value("${FEATUREFLAG_SEED:false}") boolean forced) {
		this.mongoUri = mongoUri;
		this.forced = forced;
	}

	public boolean permitsSeeding() {
		return forced || targetsLocalMachine(mongoUri);
	}

	public boolean isForced() {
		return forced;
	}

	/** Every host in the connection string must be local; an unparseable or SRV (DNS discovered) URI never is. */
	static boolean targetsLocalMachine(String uri) {
		try {
			ConnectionString connectionString = new ConnectionString(uri);

			if (connectionString.isSrvProtocol()) {
				return false;
			}

			List<String> hosts = connectionString.getHosts();

			return !hosts.isEmpty() && hosts.stream().allMatch(SeedGuard::isLocalHost);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean isLocalHost(String hostAndPort) {
		try {
			return LOCAL_HOSTS.contains(new ServerAddress(hostAndPort).getHost().toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
