package org.opensingular.dbuserprovider.persistence;

import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.NotImplementedException;
import at.favre.lib.crypto.bcrypt.BCrypt;
import org.opensingular.dbuserprovider.DBUserStorageException;
import org.opensingular.dbuserprovider.model.QueryConfigurations;
import org.opensingular.dbuserprovider.util.PBKDF2SHA256HashingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil.Pageable;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

@JBossLog
public class UserRepository {

    private DataSourceProvider dataSourceProvider;
    private QueryConfigurations queryConfigurations;

    public UserRepository(DataSourceProvider dataSourceProvider, QueryConfigurations queryConfigurations) {
        this.dataSourceProvider = dataSourceProvider;
        this.queryConfigurations = queryConfigurations;
    }

    private <T> T doQuery(String query, Pageable pageable, Function<ResultSet, T> resultTransformer, Object... params) {
        Optional<DataSource> dataSourceOpt = dataSourceProvider.getDataSource();
        if (dataSourceOpt.isPresent()) {
            DataSource dataSource = dataSourceOpt.get();
            try (Connection c = dataSource.getConnection()) {
                if (pageable != null) {
                    query = PagingUtil.formatScriptWithPageable(query, pageable, queryConfigurations.getRDBMS());
                }
                log.infov("Query: {0} params: {1} ", query, Arrays.toString(params));
                try (PreparedStatement statement = c.prepareStatement(query)) {
                    if (params != null) {
                        for (int i = 1; i <= params.length; i++) {
                            statement.setObject(i, params[i - 1]);
                        }
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        return resultTransformer.apply(rs);
                    }
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return null;
        }
        return null;
    }

    private boolean doNonQuery(String query, Object... params) {
        Optional<DataSource> dataSourceOpt = dataSourceProvider.getDataSource();
        if (dataSourceOpt.isPresent()) {
            DataSource dataSource = dataSourceOpt.get();
            try (Connection c = dataSource.getConnection()) {
                log.infov("Query: {0} params: {1} ", query, Arrays.toString(params));
                try (PreparedStatement statement = c.prepareStatement(query)) {
                    if (params != null) {
                        for (int i = 1; i <= params.length; i++) {
                            statement.setObject(i, params[i - 1]);
                        }
                    }
                    return statement.execute();
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return false;
        }
        return false;
    }

    private List<Map<String, String>> readMap(ResultSet rs) {
        try {
            List<Map<String, String>> data = new ArrayList<>();
            Set<String> columnsFound = new HashSet<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String columnLabel = rs.getMetaData().getColumnLabel(i);
                columnsFound.add(columnLabel);
            }
            while (rs.next()) {
                Map<String, String> result = new HashMap<>();
                for (String col : columnsFound) {
                    result.put(col, rs.getString(col));
                }
                data.add(result);
            }
            return data;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    private Integer readInt(ResultSet rs) {
        try {
            return rs.next() ? rs.getInt(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    private Boolean readBoolean(ResultSet rs) {
        try {
            return rs.next() ? rs.getBoolean(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    private String readString(ResultSet rs) {
        try {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    public List<Map<String, String>> getAllUsers() {
        return doQuery(queryConfigurations.getListAll(), null, this::readMap);
    }

    public int getUsersCount(String search) {
        if (search == null || search.isEmpty()) {
            return Optional.ofNullable(doQuery(queryConfigurations.getCount(), null, this::readInt)).orElse(0);
        } else {
            String query = String.format("select count(*) from (%s) count", queryConfigurations.getFindBySearchTerm());
            return Optional.ofNullable(doQuery(query, null, this::readInt, search)).orElse(0);
        }
    }

    public Map<String, String> findUserById(String id) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindById(), null, this::readMap, id))
                .orElse(Collections.emptyList())
                .stream().findFirst().orElse(null);
    }

    public Optional<Map<String, String>> findUserByUsername(String username) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindByUsername(), null, this::readMap, username))
                .orElse(Collections.emptyList())
                .stream().findFirst();
    }

    public List<Map<String, String>> findUsers(String search, PagingUtil.Pageable pageable) {
        if (search == null || search.isEmpty()) {
            return doQuery(queryConfigurations.getListAll(), pageable, this::readMap);
        }

        return doQuery(queryConfigurations.getFindBySearchTerm(), pageable, this::readMap, search);
    }

    public String doHash(String password, String salt, String hashFunction) {
        MessageDigest digest = DigestUtils.getDigest(hashFunction);

        if (salt.length() != 0) {
            try {
                byte[] decodedBytes = Base64.getDecoder().decode(salt.trim());
                byte[] pwdBytesTmp = StringUtils.getBytesUtf8(password);
                byte[] pwdBytes = new byte[pwdBytesTmp.length + decodedBytes.length];
                System.arraycopy(pwdBytesTmp, 0, pwdBytes, 0, pwdBytesTmp.length);
                System.arraycopy(decodedBytes, 0, pwdBytes, pwdBytesTmp.length, decodedBytes.length);
                return Base64.getEncoder().encodeToString(digest.digest(pwdBytes));
            } catch (Exception e) {

                throw new DBUserStorageException(e.getMessage(), e);
            }
        }
        byte[] pwdBytes = StringUtils.getBytesUtf8(password);
        return Hex.encodeHexString(digest.digest(pwdBytes));
    }

    public boolean validateCredentials(String username, String password) {
        String hash = Optional
                .ofNullable(doQuery(queryConfigurations.getFindPasswordHash(), null, this::readString, username))
                .orElse("");
        if (queryConfigurations.isBlowfish()) {
            return !hash.isEmpty() && BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        }
        String hashFunction = queryConfigurations.getHashFunction();
        String[] components = hash.split("\\$");
        if (hashFunction.equals("PBKDF2-SHA256")) {
            return new PBKDF2SHA256HashingUtil(password, components[2], Integer.valueOf(components[1]))
                    .validatePassword(components[3]);
        }

        return Objects.equals(doHash(password, components[1], hashFunction), components[0]);

    }

    public boolean updateCredentials(String username, String password) {
        String hash = Optional
                .ofNullable(doQuery(queryConfigurations.getFindPasswordHash(), null, this::readString, username))
                .orElse("");
        String hashFunction = queryConfigurations.getHashFunction();
        String[] components = hash.split("\\$");
        if (queryConfigurations.isBlowfish()) {
            throw new NotImplementedException("Password update for Blowfish not supported");
        }
        if (hashFunction.equals("PBKDF2-SHA256")) {
            throw new NotImplementedException("Password update for PBKDF2 not supported");
        }

        String passHash = doHash(password, components[1], hashFunction).concat("$").concat(components[1]);
        doNonQuery(queryConfigurations.setPasswordHash(), passHash, username);

        return true;

    }

    public boolean removeUser() {
        return queryConfigurations.getAllowKeycloakDelete();
    }
}
