package com.despegar.jdbc.galera;

import com.despegar.jdbc.galera.consistency.ConsistencyLevel;
import com.despegar.jdbc.galera.listener.GaleraClientListener;
import com.despegar.jdbc.galera.listener.GaleraClientLoggingListener;
import com.despegar.jdbc.galera.policies.ElectionNodePolicy;
import com.despegar.jdbc.galera.policies.RoundRobinPolicy;
import com.despegar.jdbc.galera.settings.ClientSettings;
import com.despegar.jdbc.galera.settings.DiscoverSettings;
import com.despegar.jdbc.galera.settings.PoolSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public class GaleraClient extends AbstractGaleraDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(GaleraClient.class);

    protected Map<String, GaleraNode> nodes = new ConcurrentHashMap<String, GaleraNode>();
    private List<String> activeNodes = new CopyOnWriteArrayList<String>();
    private List<String> downedNodes = new CopyOnWriteArrayList<String>();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private GaleraDB galeraDB;
    private PoolSettings poolSettings;
    private PoolSettings internalPoolSettings;
    private DiscoverSettings discoverSettings;
    private ClientSettings clientSettings;
    private Runnable discoverRunnable = new Runnable() {
        @Override
        public void run() {
            discovery();
        }
    };

    protected GaleraClient(ClientSettings clientSettings, DiscoverSettings discoverSettings, GaleraDB galeraDB, PoolSettings poolSettings,
                           PoolSettings internalPoolSettings) {
        this.galeraDB = galeraDB;
        this.poolSettings = poolSettings;
        this.internalPoolSettings = internalPoolSettings;
        this.discoverSettings = discoverSettings;
        this.clientSettings = clientSettings;
        registerNodes(clientSettings.seeds);
        startDiscovery(discoverSettings.discoverPeriod);
    }

    private void discovery() {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Discovering Galera cluster...");
            }
            discoverActiveNodes();
            testDownedNodes();

            if (LOG.isDebugEnabled()) {
                LOG.debug("Active nodes: {},  Downed nodes: {}", activeNodes, downedNodes);
            }
        } catch (Throwable reason) {
            LOG.error("Galera discovery failed", reason);
        }
    }

    private void testDownedNodes() {
        for (String downedNode : downedNodes) {
            try {
                discover(downedNode);
                if (nodes.containsKey(downedNode) && !(nodes.get(downedNode).status().isDonor() && discoverSettings.ignoreDonor) && nodes.get(downedNode)
                        .status().isPrimary()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Will activate a previous downed node: {}", downedNode);
                    }
                    activate(downedNode);
                }
            } catch (Exception e) {
                down(downedNode, e.getMessage());
            }
        }
    }

    private void activate(String downedNode) {
        if (!activeNodes.contains(downedNode)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Activating node:  {}", downedNode);
            }

            nodes.get(downedNode).onActivate();
            activeNodes.add(downedNode);
            downedNodes.remove(downedNode);

            clientSettings.galeraClientListener.onActivatingNode(downedNode);
        }
    }

    private void down(String node, String cause) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Marking node {} as down due to {}", node, cause);
        }
        activeNodes.remove(node);
        if (!downedNodes.contains(node)) {
            downedNodes.add(node);
        }
        closeConnections(node);

        clientSettings.galeraClientListener.onMarkingNodeAsDown(node, cause);
    }

    private void discoverActiveNodes() {
        for (String node : activeNodes) {
            try {
                discover(node);
            } catch (Exception e) {
                down(node, "failure in connection. " + e.getMessage());
            }
        }
    }

    private void removeNode(String node) {
        activeNodes.remove(node);
        downedNodes.remove(node);
        shutdownGaleraNode(node);
        nodes.remove(node);

        clientSettings.galeraClientListener.onRemovingNode(node);
    }

    private void closeConnections(String node) {
        GaleraNode galeraNode = nodes.get(node);
        if (galeraNode != null) {
            galeraNode.onDown();
        }
    }

    private void shutdownGaleraNode(String node) {
        LOG.info("Shutting down galera node {}", node);
        GaleraNode galeraNode = nodes.get(node);
        if (galeraNode != null) {
            galeraNode.shutdown();
        }
    }

    private void discover(String node) throws Exception {
        LOG.trace("Discovering {}...", node);
        GaleraNode galeraNode = nodes.get(node);

        GaleraStatus status = null;
        if (clientSettings.testMode) {
            status = GaleraStatus.buildTestStatusOk(node);
        } else {
            galeraNode.refreshStatus();
            status = galeraNode.status();
        }

        if (!status.isPrimary()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("On discover - Non primary node {}", node);
            }
            down(node, "non Primary");
            return;
        }

        if (!status.isSynced() && (discoverSettings.ignoreDonor || !status.isDonor())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("On discover - State not ready [{}] - Ignore donor [{}] : {}", status.state(), discoverSettings.ignoreDonor, node);
            }
            down(node, "state not ready: " + status.state());
            return;
        }

        Collection<String> discoveredNodes = status.getClusterNodes();
        for (String discoveredNode : discoveredNodes) {
            if (isNew(discoveredNode)) {
                LOG.info("Found new node {}. Actual nodes {}", discoveredNode, nodes.keySet());
                registerNode(discoveredNode);
            }
        }
        if (!discoveredNodes.contains(node)) {
            removeNode(node);
        } else {
            if (!isActive(node) && !(status.isDonor() && discoverSettings.ignoreDonor)) {
                LOG.info("Will activate a discovered node: {}", node);
                activate(node);
            }
        }
    }

    private boolean isActive(String node) {
        return activeNodes.contains(node);
    }

    private boolean isNew(String discoveredNode) {
        return !nodes.containsKey(discoveredNode);
    }

    private void startDiscovery(long discoverPeriod) {
        if (!clientSettings.testMode) {
            scheduler.scheduleAtFixedRate(discoverRunnable, 0, discoverPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void registerNodes(Collection<String> seeds) {
        for (String seed : seeds) {
            if (isNew(seed)) {
                registerNode(seed);
            }
        }
    }

    private void registerNode(String node) {
        LOG.info("Registering Galera node: {}", node);
        try {
            nodes.put(node, new GaleraNode(node, galeraDB, poolSettings, internalPoolSettings, clientSettings.testMode));
            discover(node);
        } catch (Exception e) {
            down(node, "failure in connection. " + e.getMessage());
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return selectNode(null).getConnection();
    }

    /**
     * @param consistencyLevel   Set the consistencyLevel needed.
     * @param electionNodePolicy Policy to choose the node that will get a connection. If it is null, we will use the default policy configured on client.
     * @return a {@link Connection}
     * @throws SQLException
     */
    public Connection getConnection(ConsistencyLevel consistencyLevel, ElectionNodePolicy electionNodePolicy) throws SQLException {
        ElectionNodePolicy policy = (electionNodePolicy != null) ? electionNodePolicy : clientSettings.defaultNodeSelectionPolicy;
        GaleraNode galeraNode = selectNode(policy);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Getting connection [{}] from node {}", policy.getName(), galeraNode.node);
        }
        return galeraNode.getConnection(consistencyLevel);
    }

    protected GaleraNode selectNode(ElectionNodePolicy electionNodePolicy) {
        return getActiveGaleraNode(1, electionNodePolicy);
    }

    private GaleraNode getActiveGaleraNode(int retry, ElectionNodePolicy electionNodePolicy) {
        if (retry <= clientSettings.retriesToGetConnection) {
            try {
                ElectionNodePolicy policy = (electionNodePolicy != null) ?
                        electionNodePolicy :
                        clientSettings.defaultNodeSelectionPolicy;
                GaleraNode galeraNode = nodes.get(policy.chooseNode(activeNodes));

                return galeraNode != null ? galeraNode : getActiveGaleraNode(++retry, electionNodePolicy);
            } catch (Exception exception) {
                LOG.warn("Error getting active galera node. Retry {}/{}. Reason {}", retry, clientSettings.retriesToGetConnection, exception);
                return getActiveGaleraNode(++retry, electionNodePolicy);
            }
        } else {
            LOG.error("NoHostAvailableException selecting an active galera node. Max attempts reached");
            throw new NoHostAvailableException();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down Galera Client...");
        scheduler.shutdown();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        for (GaleraNode galeraNode : nodes.values()) {
            if (galeraNode.getLogWriter() != null) { return galeraNode.getLogWriter(); }
        }

        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        for (GaleraNode galeraNode : nodes.values()) {

            galeraNode.setLogWriter(out);
        }
    }

    public static class Builder {

        private boolean testMode = false;
        private String database;
        private String user;
        private String password;
        private String jdbcUrlPrefix;
        private String jdbcUrlSeparator;
        private String seeds;
        private int maxConnectionsPerHost;
        private int minConnectionsIdlePerHost;
        private long discoverPeriod;
        private long connectTimeout;
        private long connectionTimeout;
        private long readTimeout;
        private long idleTimeout;
        private boolean ignoreDonor = true;
        private int retriesToGetConnection = 3;
        private boolean autocommit = true; //JDBC default.
        private boolean readOnly = false;
        private String isolationLevel = "TRANSACTION_READ_COMMITTED";
        private ConsistencyLevel consistencyLevel;
        private GaleraClientListener listener;
        private ElectionNodePolicy nodeSelectionPolicy = new RoundRobinPolicy();

        public GaleraClient build() {
            ClientSettings clientSettings =
                    new ClientSettings(
                            seeds(),
                            retriesToGetConnection,
                            (listener != null) ? listener : new GaleraClientLoggingListener(),
                            (nodeSelectionPolicy != null) ? nodeSelectionPolicy : new RoundRobinPolicy(),
                            testMode);
            DiscoverSettings discoverSettings = new DiscoverSettings(discoverPeriod, ignoreDonor);
            GaleraDB galeraDB =
                    new GaleraDB(database, user, password,
                                 (jdbcUrlPrefix != null) ? jdbcUrlPrefix : GaleraDB.MYSQL_URL_PREFIX,
                                 (jdbcUrlSeparator != null) ? jdbcUrlSeparator : GaleraDB.MYSQL_URL_SEPARATOR);
            PoolSettings poolSettings = new PoolSettings(maxConnectionsPerHost, minConnectionsIdlePerHost, connectTimeout, connectionTimeout, readTimeout,
                                                         idleTimeout, autocommit, readOnly, isolationLevel, consistencyLevel);
            PoolSettings internalPoolSettings = new PoolSettings(8, 4, connectTimeout, connectionTimeout, readTimeout,
                                                                 idleTimeout, false, true, isolationLevel, null);

            return new GaleraClient(clientSettings, discoverSettings, galeraDB, poolSettings, internalPoolSettings);
        }


        public Builder seeds(String seeds) {
            this.seeds = seeds;
            return this;
        }

        public Builder jdbcUrlPrefix(String jdbcUrlPrefix) {
            this.jdbcUrlPrefix = jdbcUrlPrefix;
            return this;
        }

        public Builder jdbcUrlSeparator(String jdbcUrlSeparator) {
            this.jdbcUrlSeparator = jdbcUrlSeparator;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder maxConnectionsPerHost(int maxConnectionsPerHost) {
            this.maxConnectionsPerHost = maxConnectionsPerHost;
            return this;
        }

        public Builder minConnectionsIdlePerHost(int minConnectionsPerHost) {
            this.minConnectionsIdlePerHost = minConnectionsPerHost;
            return this;
        }

        public Builder connectTimeout(long connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder connectTimeout(long connectTimeout, TimeUnit timeUnit) {
            return connectTimeout(timeUnit.toMillis(connectTimeout));
        }

        public Builder connectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder connectionTimeout(long connectionTimeout, TimeUnit timeUnit) {
            return connectionTimeout(timeUnit.toMillis(connectionTimeout));
        }

        public Builder listener(GaleraClientListener galeraClientListener) {
            this.listener = galeraClientListener;
            return this;
        }

        public Builder nodeSelectionPolicy(ElectionNodePolicy defaultPolicy) {
            this.nodeSelectionPolicy = defaultPolicy;
            return this;
        }

        public Builder autocommit(boolean autocommit) {
            this.autocommit = autocommit;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder isolationLevel(String isolationLevel) {
            this.isolationLevel = isolationLevel;
            return this;
        }

        public Builder consistencyLevel(ConsistencyLevel consistencyLevel) {
            this.consistencyLevel = consistencyLevel;
            return this;
        }

        private ArrayList<String> seeds() {
            return new ArrayList<String>(Arrays.asList(seeds.split(",")));
        }

        public Builder testMode(boolean testMode) {
            this.testMode = testMode;
            return this;
        }

        public Builder discoverPeriod(long discoverPeriod) {
            this.discoverPeriod = discoverPeriod;
            return this;
        }

        public Builder discoverPeriod(long discoverPeriod, TimeUnit timeUnit) {
            return discoverPeriod(timeUnit.toMillis(discoverPeriod));
        }

        public Builder readTimeout(long timeout) {
            this.readTimeout = timeout;
            return this;
        }

        public Builder idleTimeout(long timeout) {
            this.idleTimeout = timeout;
            return this;
        }

        public Builder idleTimeout(long idleTimeout, TimeUnit timeUnit) {
            return idleTimeout(timeUnit.toMillis(idleTimeout));
        }

        public Builder ignoreDonor(boolean ignore) {
            this.ignoreDonor = ignore;
            return this;
        }

        public Builder retriesToGetConnection(int retriesToGetConnection) {
            this.retriesToGetConnection = retriesToGetConnection;
            return this;
        }

        public Builder readTimeout(long timeout, TimeUnit timeUnit) {
            return readTimeout(timeUnit.toMillis(timeout));
        }
    }
}
