package org.achfrag.crypto.bot;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.achfrag.trading.crypto.bitfinex.BitfinexApiBroker;
import org.achfrag.trading.crypto.bitfinex.BitfinexOrderBuilder;
import org.achfrag.trading.crypto.bitfinex.entity.APIException;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexCurrencyPair;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexOrder;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexOrderType;
import org.achfrag.trading.crypto.bitfinex.entity.ExchangeOrder;
import org.achfrag.trading.crypto.bitfinex.entity.Wallet;
import org.bboxdb.commons.MathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasePortfolioManager {
	
	/**
	 * The maximal investmet rate
	 */
	private final static double MAX_INVESTMENT_RATE = 0.9;
	
	/**
	 * The bitfinex api broker
	 */
	private BitfinexApiBroker bitfinexApiBroker;
	
	/**
	 * The order manager
	 */
	protected final PortfolioOrderManager orderManager;
	
	/**
	 * Simulate or real trading
	 */
	public final static boolean SIMULATION = true;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(BasePortfolioManager.class);

	public BasePortfolioManager(BitfinexApiBroker bitfinexApiBroker) {
		this.bitfinexApiBroker = bitfinexApiBroker;
		this.orderManager = new PortfolioOrderManager(bitfinexApiBroker);
	}

	/**
	 * Place the entry orders for the market
	 * @param currencyPair
	 * @throws APIException 
	 */
	public void placeEntryOrders(final Map<BitfinexCurrencyPair, Double> entries) 
			throws InterruptedException, APIException {
		
		logger.info("Processing entry orders {}", entries);
		
		// Cancel old open entry orders
		cancelRemovedEntryOrders(entries);
		
		// Cancel old and changed orders
		cancelOldChangedOrders(entries);
		
		// Place the new entry orders
		placeNewEntryOrders(entries);
	}

	/**
	 * Adjust the entry orders
	 * @param entries
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void cancelOldChangedOrders(final Map<BitfinexCurrencyPair, Double> entries)
			throws APIException, InterruptedException {
		
		final double positionSize = positionSizeInUSD(entries.size());
		
		// Check current limits and position sizes
		for(final BitfinexCurrencyPair currency : entries.keySet()) {
			final ExchangeOrder order = getOpenOrderForSymbol(currency.toBitfinexString());
			
			// No old order present
			if(order == null) {
				continue;
			}
			
			final double entryPrice = entries.get(currency);
			
			if(order.getAmount() == positionSize && order.getPrice() == entryPrice) {
				logger.info("Order for {} is fine", currency);
			} else {
				logger.info("Cancel entry order for {}, values have changed", currency);	

				if(! SIMULATION) {
					orderManager.cancelOrderAndWaitForCompletion(order.getOrderId());
				}
			}
		}
	}
	
	/**
	 * Place the new entry orders
	 * @param entries
	 * @throws APIException 
	 * @throws InterruptedException 
	 */
	private void placeNewEntryOrders(final Map<BitfinexCurrencyPair, Double> entries) throws APIException, InterruptedException {
		
		final double positionSizeUSD = positionSizeInUSD(entries.size());
		
		// Check current limits and position sizes
		for(final BitfinexCurrencyPair currency : entries.keySet()) {
			final ExchangeOrder order = getOpenOrderForSymbol(currency.toBitfinexString());
			final double entryPrice = entries.get(currency);
			
			final double positionSize = calculatePositionSize(entryPrice, positionSizeUSD);

			// Old order present
			if(order != null) {
				logger.info("Not placing a new order for {}, old order still active", currency);
				continue;
			}
			
			final BitfinexOrder newOrder = BitfinexOrderBuilder
					.create(currency, getOrderType(), positionSize)
					.withPrice(entryPrice)
					.setPostOnly()
					.build();
	
			if(! SIMULATION) {
				orderManager.placeOrderAndWaitUntilActive(newOrder);
			}
		}
	}

	/**
	 * Cancel the removed entry orders 
	 * Position is at the moment not interesting for an entry
	 * 
	 * @param entries
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void cancelRemovedEntryOrders(final Map<BitfinexCurrencyPair, Double> entries)
			throws APIException, InterruptedException {
		
		final List<ExchangeOrder> entryOrders = getAllOpenEntryOrders();
		
		for(final ExchangeOrder order : entryOrders) {
			final String symbol = order.getSymbol();
			final BitfinexCurrencyPair currencyPair = BitfinexCurrencyPair.fromSymbolString(symbol);
			
			if(! entries.containsKey(currencyPair)) {
				logger.info("Entry order for {} is not contained, canceling", currencyPair);
				
				if(! SIMULATION) {
					orderManager.cancelOrderAndWaitForCompletion(order.getOrderId());
				}
			}
		}
	}
	
	/**
	 * Place the stop loss orders
	 * @param exits
	 */
	public void placeExitOrders(final Map<BitfinexCurrencyPair, Double> exits) 
			throws InterruptedException, APIException {
		
		logger.info("Process exit orders {}", exits);
		
		moveExitOrders(exits);
	}

	/**
	 * Move the exit orders
	 * @param exits
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void moveExitOrders(final Map<BitfinexCurrencyPair, Double> exits)
			throws APIException, InterruptedException {
		
		for(final BitfinexCurrencyPair currency : exits.keySet()) {
			final ExchangeOrder order = getOpenOrderForSymbol(currency.toBitfinexString());
			final double exitPrice = exits.get(currency);
			
			// Check old orders
			if(order != null) {
				if(order.getPrice() != exitPrice) {
					logger.info("Exit price has moved form {} to {}, canceling order", 
							order.getPrice(), exitPrice);
					
					orderManager.cancelOrderAndWaitForCompletion(order.getOrderId());
				} else {
					continue;
				}
			} 
			
			final double positionSize = getPositionSizeForCurrency(currency.getCurrency1());
		
			// * -1.0 for sell order
			final double positionSizeSell = positionSize * -1.0;
			
			final BitfinexOrder newOrder = BitfinexOrderBuilder
					.create(currency, getOrderType(), positionSize)
					.withPrice(positionSizeSell)
					.setPostOnly()
					.build();
	
			if(! SIMULATION) {
				orderManager.placeOrderAndWaitUntilActive(newOrder);
			}
		}
	}
	
	/**
	 * Calculate the positions size in USD
	 * @return
	 */
	private double positionSizeInUSD(final int positions) throws APIException {
		final Wallet wallet = getWalletForCurrency("USD");
		return (wallet.getBalance() * MAX_INVESTMENT_RATE) / positions;
	}
	

	/**
	 * Calculate the positon size
	 * @param upperValue
	 * @return
	 * @throws APIException 
	 */
	private double calculatePositionSize(final double entryPrice, final double positionSizeInUSD)  {
		final double positionSize = (positionSizeInUSD / entryPrice);
		return MathUtil.round(positionSize, 6);
	}

	/**
	 * Get the used wallet type 
	 * @return
	 */
	private String getWalletType() {
		return Wallet.WALLET_TYPE_EXCHANGE;
	}
	
	/**
	 * Get the used order type
	 * @return 
	 */
	public BitfinexOrderType getOrderType() {
		return BitfinexOrderType.EXCHANGE_STOP;
	}
	
	/**
	 * Get the open stop loss order
	 * @param symbol
	 * @param openOrders
	 * @return
	 * @throws APIException 
	 */
	private ExchangeOrder getOpenOrderForSymbol(final String symbol) throws APIException {
		
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getSymbol().equals(symbol))
			.findAny()
			.orElse(null);
	}
	
	/**
	 * Get all open entry orders
	 * @return 
	 * @throws APIException 
	 */
	private List<ExchangeOrder> getAllOpenEntryOrders() throws APIException {
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getAmount() > 0)
			.collect(Collectors.toList());
	}
	
	/**
	 * Get all open entry orders
	 * @return 
	 * @throws APIException 
	 */
	private List<ExchangeOrder> getAllOpenExitOrders() throws APIException {
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getAmount() <= 0)
			.collect(Collectors.toList());
	}
	
	/**
	 * Get the position size for the symbol
	 * @param symbol
	 * @return
	 * @throws APIException 
	 */
	private double getPositionSizeForCurrency(final String currency) throws APIException {
		final Wallet wallet = getWalletForCurrency(currency);
		return wallet.getBalance();
	}
 	
	/**
	 * Get the exchange wallet
	 * @param currency 
	 * @return
	 * @throws APIException 
	 */
	private Wallet getWalletForCurrency(final String currency) throws APIException {
		return bitfinexApiBroker.getWallets()
			.stream()
			.filter(w -> w.getWalletType().equals(getWalletType()))
			.filter(w -> w.getCurreny().equals(currency))
			.findFirst()
			.orElse(null);
	}
	
	/**
	 * Is the given position open
	 * @param currency
	 * @return
	 * @throws APIException 
	 */
	public boolean isPositionOpen(final String currency) throws APIException {
		final Wallet wallet = getWalletForCurrency(currency);
		
		if(wallet == null) {
			// Unused wallets are not included in API communication
			logger.debug("Wallet for {} is null", currency);
			return false;
		}
		
		if(wallet.getBalance() > 0.002) {
			return true;
		}
		
		return false;
	}
	
}