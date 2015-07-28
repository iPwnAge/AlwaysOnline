package me.johnnywoof.databases;

import com.mysql.jdbc.exceptions.jdbc4.CommunicationsException;
import com.mysql.jdbc.exceptions.jdbc4.MySQLNonTransientConnectionException;

import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MySQLDatabase implements Database {

	private static final String selectSQLStatement = "SELECT uuid,ip FROM always_online WHERE name = ?";
	private static final String insertSQLStatement = "INSERT INTO always_online (name,ip,uuid) VALUES(?,?,?) ON DUPLICATE KEY UPDATE ip = VALUES(ip), uuid = VALUES(uuid)";

	private final String host;
	private final int port;
	private final String database;
	private final String username;
	private final String password;

	private Statement statement = null;

	private final ConcurrentHashMap<String, PlayerData> cache = new ConcurrentHashMap<>();

	public MySQLDatabase(String host, int port, String database, String username, String password) throws SQLException {

		this.host = host;
		this.port = port;
		this.database = database;
		this.username = username;
		this.password = password;

		this.connect();

	}

	private void connect() throws SQLException {

		if (this.statement != null) {

			try {

				this.statement.close();

			} catch (SQLException e) {
				e.printStackTrace();
			}

			this.statement = null;

		}

		this.statement = DriverManager.getConnection("jdbc:mysql://" + this.host + ":" + this.port + "/"
				+ this.database, this.username, this.password).createStatement();

		if (!this.doesTableExist("always_online")) {

			this.statement.executeUpdate("CREATE TABLE `always_online` ( `name` CHAR(16) NOT NULL , `ip` CHAR(15) NOT NULL , `uuid` CHAR(36) NOT NULL , PRIMARY KEY (`name`)) ENGINE = MyISAM; ");

		}

	}

	@Override
	public String getIP(String username) {

		PlayerData playerData = this.cache.get(username);

		if (playerData != null) {

			return playerData.ipAddress;

		} else {

			if (this.loadDataFromSQL(username)) {

				return this.cache.get(username).ipAddress;

			}

		}

		return null;

	}

	private boolean loadDataFromSQL(String username) {

		if (this.statement != null) {

			PlayerData playerData = null;

			try {

				PreparedStatement preparedStatement = this.statement.getConnection().prepareStatement(selectSQLStatement);
				preparedStatement.setString(1, username);

				ResultSet rs = preparedStatement.executeQuery();

				if (rs.next()) {

					playerData = new PlayerData(rs.getString(2), UUID.fromString(rs.getString(1)));

				}

				rs.close();
				preparedStatement.close();

			} catch (CommunicationsException | MySQLNonTransientConnectionException e) {

				try {

					this.connect();
					return this.loadDataFromSQL(username);

				} catch (SQLException e1) {
					e1.printStackTrace();
				}

			} catch (SQLException e) {

				e.printStackTrace();

			}

			if (playerData != null) {

				this.cache.put(username, playerData);
				return true;

			}

		}

		return false;

	}

	@Override
	public UUID getUUID(String username) {

		PlayerData playerData = this.cache.get(username);

		if (playerData != null) {

			return playerData.uuid;

		} else {

			if (this.loadDataFromSQL(username)) {

				return this.cache.get(username).uuid;

			}

		}

		return null;

	}

	@Override
	public void updatePlayer(String username, String ip, UUID uuid) {

		this.cache.put(username, new PlayerData(ip, uuid));

		if (this.statement != null) {

			try {

				Connection connection = this.statement.getConnection();

				if (connection != null) {

					PreparedStatement preparedStatement = connection.prepareStatement(insertSQLStatement);

					preparedStatement.setString(1, username);
					preparedStatement.setString(2, ip);
					preparedStatement.setString(3, uuid.toString());

					preparedStatement.execute();

					preparedStatement.close();

				}

			} catch (SQLException e) {

				e.printStackTrace();

			}

		}

	}

	@Override
	public void save() throws Exception {

		if (this.statement != null) {

			Connection connection = this.statement.getConnection();

			if (connection != null) {

				PreparedStatement preparedStatement = connection.prepareStatement(insertSQLStatement);

				int i = 0;

				for (Map.Entry<String, PlayerData> en : this.cache.entrySet()) {

					preparedStatement.setString(1, en.getKey());
					preparedStatement.setString(2, en.getValue().ipAddress);
					preparedStatement.setString(3, en.getValue().uuid.toString());

					preparedStatement.addBatch();
					i++;

					if (i % 1000 == 0 || i == this.cache.size()) {
						preparedStatement.executeBatch(); // Execute every 1000 items or when full.
					}

				}

				preparedStatement.close();

			}

		}

	}

	@Override
	public void resetCache() {
		this.cache.clear();
	}

	/**
	 * Determines if a table exists
	 *
	 * @param tableName The table name
	 * @return If the table exists
	 */
	private boolean doesTableExist(String tableName) throws SQLException {
		ResultSet rs = this.statement.getConnection().getMetaData().getTables(null, null, tableName, null);
		if (rs.next()) {
			rs.close();
			return true;
		}
		rs.close();
		return false;
	}

}
