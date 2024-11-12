package com.aleatory.price.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aleatory.price.events.SPXCloseReceivedEvent;

@Repository
public class SPXHistoryDao {
	private static Logger logger = LoggerFactory.getLogger(SPXHistoryDao.class);

	@Autowired
	private NamedParameterJdbcTemplate template;

	private static final String LAST_SPX_CLOSE_QUERY = "SELECT close FROM spx_history WHERE trade_date=(SELECT MAX(trade_date) FROM spx_history WHERE trade_date < CURRENT_DATE);";

	private static final String INSERT_SPX_CLOSE_SQL = "INSERT INTO public.spx_history (trade_date, close, is_final) VALUES (:forDate, :price, :finalPrice)\n"
			+ "	ON CONFLICT (trade_date) DO UPDATE SET close=:price, is_final=:finalPrice WHERE :finalPrice OR NOT spx_history.is_final;";
	
	private static final String ALL_SPX_CLOSE_DATES_QUERY = "SELECT trade_date FROM spx_history ORDER BY trade_date;";

	public Double getLastSPXClose() {
		Double lastSPXClose = template.queryForObject(LAST_SPX_CLOSE_QUERY, Collections.emptyMap(), Double.class);
		return lastSPXClose;
	}

	public List<LocalDate> fetchAllSPXCloses() {
		List<LocalDate> closeDates = template.query(ALL_SPX_CLOSE_DATES_QUERY, new RowMapper<LocalDate>() {

			@Override
			public LocalDate mapRow(ResultSet rs, int rowNum) throws SQLException {
				return rs.getDate("trade_date").toLocalDate();
			}
		});
		return closeDates;
	}

	/**
	 * Listens for {@link SPXCloseReceivedEvent} to store the SPX close.
	 * 
	 * @param event the event that gives us the SPX close to store
	 */
	@EventListener
	public void storeSPXClose(SPXCloseReceivedEvent event) {
		logger.info("Writing close of {} for day {} ({}) to database.", event.getPrice(), event.getForDate(), event.isFinalPrice() ? "final" : "not final");
		BeanPropertySqlParameterSource params = new BeanPropertySqlParameterSource(event);
		try {
			template.update(INSERT_SPX_CLOSE_SQL, params);
			logger.debug("Wrote close for {} to DB.", event.getForDate());
		} catch (DataAccessException e) {
			logger.error("Could not write close for {} to database.", event.getForDate(), e);
		}
	}
}
