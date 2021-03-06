package com.activequant.trading;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.activequant.aqviz.HardcoreReflectionsFactory;
import com.activequant.domainmodel.AlgoConfig;
import com.activequant.domainmodel.ETransportType;
import com.activequant.domainmodel.Future;
import com.activequant.domainmodel.Instrument;
import com.activequant.domainmodel.MarketDataInstrument;
import com.activequant.domainmodel.Stock;
import com.activequant.domainmodel.TimeStamp;
import com.activequant.domainmodel.TradeableInstrument;
import com.activequant.domainmodel.exceptions.DaoException;
import com.activequant.domainmodel.exceptions.TransportException;
import com.activequant.domainmodel.streaming.AccountDataEvent;
import com.activequant.domainmodel.streaming.InformationalEvent;
import com.activequant.domainmodel.streaming.MarketDataSnapshot;
import com.activequant.domainmodel.streaming.OrderStreamEvent;
import com.activequant.domainmodel.streaming.PNLChangeEvent;
import com.activequant.domainmodel.streaming.PositionEvent;
import com.activequant.domainmodel.streaming.StreamEvent;
import com.activequant.domainmodel.streaming.Tick;
import com.activequant.domainmodel.streaming.TimeStreamEvent;
import com.activequant.domainmodel.trade.event.OrderAcceptedEvent;
import com.activequant.domainmodel.trade.event.OrderCancelledEvent;
import com.activequant.domainmodel.trade.event.OrderEvent;
import com.activequant.domainmodel.trade.event.OrderFillEvent;
import com.activequant.domainmodel.trade.event.OrderRejectedEvent;
import com.activequant.domainmodel.trade.event.OrderReplacedEvent;
import com.activequant.domainmodel.trade.event.OrderSubmittedEvent;
import com.activequant.domainmodel.trade.event.OrderUpdateRejectedEvent;
import com.activequant.domainmodel.trade.order.LimitOrder;
import com.activequant.domainmodel.trade.order.MarketOrder;
import com.activequant.domainmodel.trade.order.Order;
import com.activequant.domainmodel.trade.order.OrderSide;
import com.activequant.domainmodel.trade.order.SingleLegOrder;
import com.activequant.domainmodel.trade.order.StopOrder;
import com.activequant.interfaces.aqviz.IVisualTable;
import com.activequant.interfaces.trading.IOrderTracker;
import com.activequant.interfaces.trading.IRiskCalculator;
import com.activequant.interfaces.trading.ITradingSystem;
import com.activequant.interfaces.trading.ITradingSystemEnvironment;
import com.activequant.interfaces.utils.IEventListener;
import com.activequant.messages.AQMessages;
import com.activequant.messages.AQMessages.BaseMessage;
import com.activequant.messages.AQMessages.BaseMessage.CommandType;
import com.activequant.messages.Marshaller;
import com.activequant.messages.MessageFactory2;
import com.activequant.trading.datamodel.AccountTable;
import com.activequant.trading.datamodel.AuditLogTable;
import com.activequant.trading.datamodel.ExecutionsTable;
import com.activequant.trading.datamodel.InstrumentTable;
import com.activequant.trading.datamodel.OrderTable;
import com.activequant.trading.datamodel.PositionTable;
import com.activequant.trading.datamodel.QuoteTable;

/**
 * Relatively smart trading system base which keeps track of various things.
 * 
 * @author ustaudinger
 * 
 */
public abstract class AbstractTSBase implements ITradingSystem {
	// performance improvements start
	private static final int BID_SIZE_COL_IDX = QuoteTable.Columns.BIDSIZE
			.colIdx();
	private static final int BID_COL_IDX = QuoteTable.Columns.BID.colIdx();
	private static final int ASK_SIZE_COL_INDX = QuoteTable.Columns.ASKSIZE
			.colIdx();
	private static final int ASK_COL_IDX = QuoteTable.Columns.ASK.colIdx();
	// performance improvements end

	private final InstrumentTable instrumentTable = new InstrumentTable(this);
	private final QuoteTable quoteTable = new QuoteTable(this);
	private final PositionTable positionTable = new PositionTable(this);
	private final ExecutionsTable executionsTable = new ExecutionsTable(this);
	private final OrderTable orderTable = new OrderTable(this);
	private final AuditLogTable auditLogTable = new AuditLogTable(this);
	private final AccountTable accountTable = new AccountTable(this);
	private final Logger log = Logger.getLogger(AbstractTSBase.class);
	protected ITradingSystemEnvironment env;
	protected TimeStamp currentTime;
	private AlgoConfig algoConfig = new AlgoConfig();
	protected IRiskCalculator riskCalculator = new PositionRiskCalculator(this);

	protected SimpleDateFormat date8 = new SimpleDateFormat("yyyyMMdd");
	protected SimpleDateFormat date8time6 = new SimpleDateFormat(
			"yyyyMMdd HH:mm:ss");

	// visuals.
	private boolean auditLog = true;
	// used to indicate whether a viz layer should be instantiated.
	private boolean vizLayer = true;
	// just used for viz and other things. not relevant.
	private long currentSlot = 0L;
	private IVisualTable instViz, quoteViz, execViz, orderViz, posViz,
			auditViz, accountViz;
	protected boolean isRunning = false;

	@Override
	public void start() throws Exception {
		isRunning = true;
	}

	@Override
	public void stop() throws Exception {
		isRunning = false;

	}

	public boolean isRunning() {
		return isRunning;
	}

	private Marshaller marshaller = new Marshaller();

	// an internal listener.
	// wrong place, but ok.
	protected IEventListener<byte[]> rawListener = new IEventListener<byte[]>() {
		@Override
		public void eventFired(byte[] event) {
			BaseMessage bm;
			try {
				bm = marshaller.demarshall(event);
				OrderEvent temp = null;
				// if (log.isDebugEnabled())
				// log.debug("Event type: " + bm.getType());
				if (bm.getType().equals(CommandType.MDS)) {
					MarketDataSnapshot mds = marshaller
							.demarshall(((AQMessages.MarketDataSnapshot) bm
									.getExtension(AQMessages.MarketDataSnapshot.cmd)));
					process(mds);
				} 
				else if(bm.getType().equals(CommandType.TICK)){			
					Tick t = marshaller.demarshall(((AQMessages.Tick) bm.getExtension(AQMessages.Tick.cmd)));
					process(t);
				}
				else
				// really need to make a proper converter finally.
				if (bm.getType().equals(CommandType.SERVER_TIME)) {

				} else if ((temp = marshaller.demarshallOrderEvent(bm)) != null) {
					OrderStreamEvent ose = new OrderStreamEvent("",
							temp.getTimeStamp(), temp);
					// let's enrich the order stream event with our ref order.
					String refOrderId = ose.getOe().getRefOrderId();
					if (refOrderId != null) {
						//
						IOrderTracker iot = env.getExchange().getOrderTracker(
								refOrderId);
						if (iot != null) {
							// we have an order tracker ... so let the order
							// tracker handle this.
							// ose.getOe().setRefOrder(iot.getOrder());
							return;
						}
					}
					process((StreamEvent) ose);
				} else if (bm.getType().equals(CommandType.POSITION_REPORT)) {
					PositionEvent pos = marshaller
							.demarshall(((AQMessages.PositionReport) bm
									.getExtension(AQMessages.PositionReport.cmd)));
					process((StreamEvent) pos);
				} else if (bm.getType().equals(CommandType.INFO_EVENT)) {
					InformationalEvent ie = marshaller
							.demarshall(((AQMessages.InfoEvent) bm
									.getExtension(AQMessages.InfoEvent.cmd)));
					process((StreamEvent) ie);
				} else if (bm.getType().equals(CommandType.NEW_ORDER)) {
					if (((AQMessages.NewOrder) bm
							.getExtension(AQMessages.NewOrder.cmd)).getResend() == 1) {
						// ok, let's add it.

						AQMessages.NewOrder no = (AQMessages.NewOrder) bm
								.getExtension(AQMessages.NewOrder.cmd);
						//

						SingleLegOrder slo = marshaller.demarshall(no);
						addOrSetOrderTable(slo);

						// ok, order being resent.
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				log.warn("Exception: ", e);
			}
		}
	};

	public void environment(ITradingSystemEnvironment env) {
		this.env = env;
		this.riskCalculator.setTransportFactory(env.getTransportFactory());

	}

	public InstrumentTable getInstrumentTable() {
		return instrumentTable;
	}

	public QuoteTable getQuoteTable() {
		return quoteTable;
	}

	public PositionTable getPositionTable() {
		return positionTable;
	}

	public ExecutionsTable getExecutionsTable() {
		return executionsTable;
	}

	public OrderTable getOrderTable() {
		return orderTable;
	}

	public AuditLogTable getAuditLogTable() {
		return auditLogTable;
	}

	public AccountTable getAccountTable() {
		return accountTable;
	}

	protected void subscribe(MarketDataInstrument mdi)
			throws TransportException {
		log.info("Subscribing to " + mdi.getId());
		// env.getTransportFactory()
		// .getReceiver(ETransportType.MARKET_DATA, mdi.getId())
		// .getMsgRecEvent().addEventListener(internalListener);

		// also add a raw message transcoder.
		env.getTransportFactory()
				.getReceiver(ETransportType.MARKET_DATA, mdi.getId())
				.getRawEvent().addEventListener(rawListener);

	}

	protected void subscribe(TradeableInstrument tdi) throws TransportException {
		log.info("Subscribing to " + tdi.getId());
		env.getTransportFactory()
				.getReceiver(ETransportType.TRAD_DATA, tdi.getId())
				.getRawEvent().addEventListener(rawListener);
	}

	protected void unsubscribe(MarketDataInstrument mdi)
			throws TransportException {
		unsubscribeMdi(mdi.getId());
	}

	protected void unsubscribeMdi(String mdiId) throws TransportException {
		log.info("Unsubscribing from " + mdiId);
		env.getTransportFactory()
				.getReceiver(ETransportType.MARKET_DATA, mdiId).getRawEvent()
				.removeEventListener(rawListener);
	}

	protected void unsubscribe(TradeableInstrument tdi)
			throws TransportException {
		unsubscribeTdi(tdi.getId());
	}

	protected void unsubscribeTdi(String tdiId) throws TransportException {
		log.info("Unsubscribing from " + tdiId);
		env.getTransportFactory().getReceiver(ETransportType.TRAD_DATA, tdiId)
				.getRawEvent().removeEventListener(rawListener);
	}

	public void addInstrument(String mdiId) throws DaoException,
			TransportException {
		addInstrument(mdiId, mdiId);
	}

	/**
	 * Adds an instrument to our environment.
	 * 
	 * @param mdiId
	 * @throws DaoException
	 * @throws TransportException
	 */
	public void addInstrument(String mdiId, String tdiId) throws DaoException,
			TransportException {
		// set up the instrument table and subscribe to one instrument.
		MarketDataInstrument mdi = env.getDaoFactory().mdiDao().load(mdiId);
		log.info("Loaded MDI (" + mdiId + "): " + mdi);

		TradeableInstrument tdi = env.getDaoFactory().tradeableDao()
				.load(tdiId);
		log.info("Loaded TDI (" + tdiId + "): " + tdi);

		//
		if (mdi == null || tdi == null) {
			throw new DaoException("Could not load " + mdiId + " or " + tdiId);
		}

		addInstrument(mdi, tdi);

	}

	public void addInstrument(MarketDataInstrument mdi, TradeableInstrument tdi)
			throws TransportException, DaoException {

		String mdiId = mdi.getId();
		String tdiId = tdi.getId();
		if (mdi.getInstrumentId() != null && !mdi.getInstrumentId().isEmpty()) {
			// try to load the instrument.
			Instrument i = env.getDaoFactory().instrumentDao()
					.load(mdi.getInstrumentId());
			if (i instanceof Future) {
				Future f = (Future) i;
				assert (f.getTickValue() != null);
				getInstrumentTable().addInstrument(
						mdiId,
						tdiId,
						f.getCurrency(),
						f.getLastTradingDate() == null ? 0L : f
								.getLastTradingDate(),
						f.getTickSize() == null ? 1.0 : f.getTickSize(),
						f.getTickValue() == null ? 1.0 : f.getTickValue(),
						0000.0, 235959.0);
			} else if (i instanceof Stock) {
				Stock s = (Stock) i;
				getInstrumentTable().addInstrument(mdiId, tdiId,
						s.getCurrency(), 0L, s.getTickSize(), s.getTickValue(),
						0000.0, 235959.0);
			} else
				getInstrumentTable().addInstrument(mdiId, tdiId, "", 0L, 1.0,
						1.0, 0000.0, 235959.0);
		} else
			// add the instrument to our list of instruments.
			getInstrumentTable().addInstrument(mdiId, tdiId, "", 0L, 1.0, 1.0,
					0.0, 235959.0);
		// add the instrument also to the rest.
		getQuoteTable().addInstrument(mdiId);
		getPositionTable().addInstrument(tdiId);

		// signal updates to our tables.
		getInstrumentTable().signalUpdate();
		getQuoteTable().signalUpdate();
		getPositionTable().signalUpdate();

		// subscribe to market data and to instrument data.
		subscribe(mdi);
		subscribe(tdi);
		// also send a portfolio resend request.
		requestPortfolio(tdi);
		// also rerequest executions ...
		requestMarketQuote(mdi);
	}

	private void requestMarketQuote(MarketDataInstrument mdi)
			throws TransportException {
		MessageFactory2 mf = new MessageFactory2();
		try {
			env.getTransportFactory()
					.getPublisher(ETransportType.MARKET_DATA,
							mdi.getId() + ":CONTROL")
					.send(mf.buildCustomCommand("resend price " + mdi.getId())
							.toByteArray());
		} catch (Exception e) {
			throw new TransportException(e);
		}
	}

	private void requestPortfolio(TradeableInstrument tdi)
			throws TransportException {
		MessageFactory2 mf = new MessageFactory2();
		try {
			// env.getTransportFactory()
			// .getPublisher(ETransportType.TRAD_DATA, tdi.getId())
			// .send(mf.buildCustomCommand("POS " + tdi.getId())
			// .toByteArray());
			env.getTransportFactory()
					.getPublisher(ETransportType.TRAD_DATA, tdi.getId())
					.send(mf.buildCustomCommand("EXEC " + tdi.getId())
							.toByteArray());
		} catch (Exception e) {
			throw new TransportException(e);
		}
	}

	/**
	 * removes an instrument from our environment.
	 * 
	 * @param mdiId
	 * @throws TransportException
	 */
	public void removeInstrument(String mdiId, String tdiId)
			throws TransportException {
		getInstrumentTable().deleteInstrument(mdiId);
		getQuoteTable().deleteInstrument(mdiId);
		getPositionTable().deleteInstrument(tdiId);
		// unsubscribe from market data and to instrument data.
		unsubscribeMdi(mdiId);
		unsubscribeTdi(tdiId);
	}

	public void initialize() throws Exception {
		// subscribe to time data.
		env.getTransportFactory().getReceiver(ETransportType.TIME.toString())
				.getRawEvent().addEventListener(rawListener);

		// subscribe to informational events with no filter.
		env.getTransportFactory().getReceiver(ETransportType.INFORMATIONAL, "")
				.getRawEvent().addEventListener(rawListener);

		// subscribe to risk data with a wild card. Might bite later down.
		env.getTransportFactory()
				.getReceiver(ETransportType.RISK_DATA.toString()).getRawEvent()
				.addEventListener(rawListener);

		if (vizLayer) {
			HardcoreReflectionsFactory hrf = new HardcoreReflectionsFactory();
			// plug in two visual tables for the underlying table models.
			instViz = hrf.getInstrumentTableViz("Instruments",
					getInstrumentTable(), env.getExchange());
			quoteViz = hrf.getQuoteTableViz("Quotes", getQuoteTable(),
					env.getExchange());
			execViz = hrf.getVisualTableViz("Executions", getExecutionsTable());
			orderViz = hrf.getOrderTableViz("Working orders", getOrderTable(),
					env.getExchange());
			posViz = hrf.getPositionTableViz("Positions", getPositionTable(),
					env.getExchange());
			auditViz = hrf.getAuditTableViz("Audit", getAuditLogTable());
			accountViz = hrf.getAccountTableViz("Account information",
					getAccountTable());
		}

		// refresh the table models.
		getInstrumentTable().signalUpdate();
		getQuoteTable().signalUpdate();
		getExecutionsTable().signalUpdate();
		getOrderTable().signalUpdate();
		getPositionTable().signalUpdate();
		getAuditLogTable().signalUpdate();
		getAccountTable().signalUpdate();
		//

	}

	public void setWindowTitles(String additionalTitle) {
		if (vizLayer) {
			instViz.setTitle("Instruments - " + additionalTitle);
			quoteViz.setTitle("Quotes - " + additionalTitle);
			execViz.setTitle("Executions - " + additionalTitle);
			orderViz.setTitle("Working orders - " + additionalTitle);
			posViz.setTitle("Positions - " + additionalTitle);
			auditViz.setTitle("Audit - " + additionalTitle);
			accountViz.setTitle("Account information - " + additionalTitle);
		}
	}

	/**
	 * Called if there is a new stream event. This method delegates on to
	 * specific process methods.
	 * 
	 * @param se
	 *            arriving stream event.
	 */
	@Override
	public void process(StreamEvent se) {
		if (se instanceof Tick) {
			process((Tick) se);
		} else if (se instanceof MarketDataSnapshot) {
			process((MarketDataSnapshot) se);
		} else if (se instanceof PositionEvent) {
			process((PositionEvent) se);
			// not logging in to audit for now ...
			// auditLog(se.getTimeStamp(), se.toString());
		} else if (se instanceof OrderStreamEvent) {
			process((OrderStreamEvent) se);

		} else if (se instanceof InformationalEvent) {
			auditLog(se.getTimeStamp(), ((InformationalEvent) se).getText());
		} else if (se instanceof AccountDataEvent) {
			process((AccountDataEvent) se);
		} else if (se instanceof PNLChangeEvent) {
			process((PNLChangeEvent) se);
		}
		// TimeStreamEvents should be handled always.
		if (se instanceof TimeStreamEvent) {
			process((TimeStreamEvent) se);
		}
	}

	private void auditLog(TimeStamp ts, String text) {
		if (auditLog) {
			log.info("AUDIT [" + ts + "] " + text);
			getAuditLogTable().addAudit(ts, text);
			getAuditLogTable().signalUpdate();
		}
	}

	public void process(PositionEvent pe) {
		getPositionTable().setPosition(pe.getTradInstId(), pe.getPrice(),
				pe.getQuantity());
		getPositionTable().signalUpdate();
		//
		riskCalculator.setPosition(pe.getTradInstId(), pe.getPrice(),
				pe.getQuantity());
		//
	}

	public void process(AccountDataEvent adevent) {
		getAccountTable().setVariable(adevent.getVariableId(),
				adevent.getValue());
		getAccountTable().signalUpdate();
	}

	private List<String> seenExecutions = new ArrayList<String>();

	public void processOrderEvent(OrderEvent oe) {
		Order refOrder = oe.getRefOrder();

		if (oe instanceof OrderFillEvent) {
			// add an execution.
			OrderFillEvent ofe = (OrderFillEvent) oe;
			//
			String execId = ofe.getExecId();
			if (execId == null) {
				return;
			}
			boolean seen = false;
			for (String s : seenExecutions) {
				if (s.equals(execId)) {
					seen = true;
					break;
				}
			}
			if (!seen) {
				seenExecutions.add(execId);
				//
				getExecutionsTable().addExecution(
						ofe.getRefOrderId(),
						ofe.getExecId(),
						ofe.getTimeStamp(),
						ofe.getOptionalInstId(),
						ofe.getSide(), ofe.getFillPrice(),
						ofe.getFillAmount());
				if (ofe.getResend() == 0) {
					// also signal the execution to the risk calculator.
					// riskCalculator.execution(ofe.getTimeStamp(),
					// ofe.getOptionalInstId(),
					// ofe.getFillPrice(),
					// // B nasty one. might bite later down.
					// (ofe.getSide().startsWith("B") ? 1.0 : -1.0)
					// * ofe.getFillAmount());
					//
					if (ofe.getLeftQuantity() == 0) {
						getOrderTable().delOrder(ofe.getRefOrderId());
					} else {
						// let's also update the ref order ...

						addOrSetOrderTable(refOrder);
					}
				}
				// log it to audit, too.
				auditLog(ofe.getTimeStamp(), ofe.toString());
			}
			getExecutionsTable().signalUpdate();
			getOrderTable().signalUpdate();
		} else if (oe instanceof OrderSubmittedEvent) {
			auditLog(oe.getTimeStamp(), oe.toString());
		} else if ((oe instanceof OrderAcceptedEvent)
				|| (oe instanceof OrderReplacedEvent)) {
			// add it to our orders table.
			addOrSetOrderTable(refOrder);
			// log it to audit, too.
			auditLog(oe.getTimeStamp(), oe.toString());
		} else if (oe instanceof OrderRejectedEvent) {
			getOrderTable().delOrder(oe.getRefOrderId());
			getOrderTable().signalUpdate();
			auditLog(oe.getTimeStamp(), oe.toString());
		} else if (oe instanceof OrderCancelledEvent) {
			getOrderTable().delOrder(oe.getRefOrderId());
			getOrderTable().signalUpdate();
			auditLog(oe.getTimeStamp(), oe.toString());
		} else if (oe instanceof OrderUpdateRejectedEvent) {
			auditLog(oe.getTimeStamp(), oe.toString());
		}
	}

	/**
	 * Order Stream events do not interfere with order tracker events.
	 * 
	 * @param ose
	 */
	public void process(OrderStreamEvent ose) {
		Order refOrder = ose.getOe().getRefOrder();
		if (refOrder == null) {
			log.warn("No ref order in order stream event.");
		}

		OrderEvent oe = ose.getOe();
		processOrderEvent(oe);

	}

	/**
	 * 
	 * Adds or sets the information in an order table, depending on the order
	 * type.
	 * 
	 * @param refOrder
	 */
	private void addOrSetOrderTable(Order refOrder) {
		if (refOrder instanceof MarketOrder) {
			MarketOrder mo = (MarketOrder) refOrder;
			getOrderTable().addOrder(refOrder.getOrderId(), mo.getTradInstId(),
					"MKT", mo.getOrderSide().toString(),
					Double.POSITIVE_INFINITY, mo.getQuantity(),
					mo.getQuantity() - mo.getOpenQuantity());
		} else if (refOrder instanceof LimitOrder) {
			LimitOrder lo = (LimitOrder) refOrder;
			getOrderTable().addOrder(refOrder.getOrderId(), lo.getTradInstId(),
					"LMT", lo.getOrderSide().toString(), lo.getLimitPrice(),
					lo.getQuantity(), lo.getQuantity() - lo.getOpenQuantity());
		} else if (refOrder instanceof StopOrder) {
			StopOrder so = (StopOrder) refOrder;
			getOrderTable().addOrder(refOrder.getOrderId(), so.getTradInstId(),
					"STP", so.getOrderSide().toString(), so.getStopPrice(),
					so.getQuantity(), so.getQuantity() - so.getOpenQuantity());
		}
		getOrderTable().signalUpdate();
	}

	/**
	 * Market Data Snapshots arrive here. At the moment, this updates only the
	 * qoute table. It should also update some sort of a DOM.
	 * 
	 * @param mds
	 */
	public void process(MarketDataSnapshot mds) {
		if (mds == null)
			return;
		// update the current mkt quotes table.
		String mdiId = mds.getMdiId();
		if (getInstrumentTable().containsInstrumentId(mdiId)) {
			int rowIndx = getInstrumentTable().getRowIndexOf(mdiId);

			// call by reference
			Object[][] row = getQuoteTable().getData();
			// update the quote table.
			if (mds.getAskPrices() != null && mds.getAskPrices().length > 0
					&& !Double.isNaN(mds.getAskPrices()[0])) {
				row[rowIndx][ASK_COL_IDX] = mds.getAskPrices()[0];
				row[rowIndx][ASK_SIZE_COL_INDX] = mds.getAskSizes()[0];
				// getQuoteTable().setValueAt(mds.getAskPrices()[0], rowIndx,
				// ASK_COL_IDX);
				// getQuoteTable().setValueAt(mds.getAskSizes()[0], rowIndx,
				// ASK_SIZE_COL_INDX);
			} else {
				row[rowIndx][ASK_COL_IDX] = "";
				row[rowIndx][ASK_SIZE_COL_INDX] = "";

				// getQuoteTable().setValueAt(null, rowIndx, ASK_COL_IDX);
				// getQuoteTable().setValueAt(null, rowIndx, ASK_SIZE_COL_INDX);
			}
			if (mds.getBidPrices() != null && mds.getBidPrices().length > 0
					&& !Double.isNaN(mds.getBidPrices()[0])) {

				row[rowIndx][BID_COL_IDX] = mds.getBidPrices()[0];
				row[rowIndx][BID_SIZE_COL_IDX] = mds.getBidSizes()[0];

				// getQuoteTable().setValueAt(mds.getBidPrices()[0], rowIndx,
				// BID_COL_IDX);
				// getQuoteTable().setValueAt(mds.getBidSizes()[0], rowIndx,
				// BID_SIZE_COL_IDX);
			} else {

				row[rowIndx][BID_COL_IDX] = "";
				row[rowIndx][BID_SIZE_COL_IDX] = "";

				// getQuoteTable().setValueAt(null, rowIndx, BID_COL_IDX);
				// getQuoteTable().setValueAt(null, rowIndx, BID_SIZE_COL_IDX);
			}
			// signaling that this row has changed.
			getQuoteTable().getRowUpdateEvent().fire(rowIndx);
			getQuoteTable().signalUpdate();

			// recalculate the current position values.
			// riskCalculator.pricesUpdated(rowIndx);

			//
		} else {
			log.info("Dropping data for unknown instrument: " + mds.getMdiId());
		}
	}

	public void process(Tick mds) {
		// update the current mkt quotes table.
		String mdiId = mds.getMdiId();
		if (getInstrumentTable().containsInstrumentId(mdiId)) {
			int row = getInstrumentTable().getRowIndexOf(mdiId);
			// update the quote table.

			getQuoteTable().setValueAt(mds.getPrice(), row,
					QuoteTable.Columns.TRADE.colIdx());
			getQuoteTable().setValueAt(mds.getQuantity(), row,
					QuoteTable.Columns.TRADESIZE.colIdx());

			getQuoteTable().getRowUpdateEvent().fire(row);
			getQuoteTable().signalUpdate();
			//
			riskCalculator.pricesUpdated(row);
			//
		} else {
			log.info("Dropping data for unknown instrument. ");
		}
	}

	/**
	 * Called when a pnl change event arrived.
	 * 
	 * @param pe
	 */
	public void process(PNLChangeEvent pe) {
		int row = getInstrumentTable().getRowIndexOfByTradeId(
				pe.getTradInstId());
		getPositionTable().setValueAt(pe.getTotalPnl(), row,
				PositionTable.Columns.PNLATLIQUIDATION.colIdx());
		getPositionTable().signalUpdate();
	}

	public void process(TimeStreamEvent se) {
		if (se instanceof TimeStreamEvent) {
			currentTime = se.getTimeStamp();
			long slot = currentTime.getNanoseconds()
					/ (1000l * 1000l * 1000l * 15l);
			if (slot != currentSlot) {
				currentSlot = slot;
				setWindowTitles(date8time6.format(currentTime.getCalendar()
						.getTime()));
			}
			// log.info("It is now " + ((TimeStreamEvent)
			// se).getTimeStamp().getDate());
		}
	}

	public boolean isVizLayer() {
		return vizLayer;
	}

	public void setVizLayer(boolean vizLayer) {
		this.vizLayer = vizLayer;
	}

	public long getCurrentTimeSlot() {
		return currentSlot;
	}

	public void setCurrentMinute(long currentMinute) {
		this.currentSlot = currentMinute;
	}

	public IVisualTable getInstViz() {
		return instViz;
	}

	public void setInstViz(IVisualTable instViz) {
		this.instViz = instViz;
	}

	public IVisualTable getQuoteViz() {
		return quoteViz;
	}

	public void setQuoteViz(IVisualTable quoteViz) {
		this.quoteViz = quoteViz;
	}

	public IVisualTable getExecViz() {
		return execViz;
	}

	public void setExecViz(IVisualTable execViz) {
		this.execViz = execViz;
	}

	public IVisualTable getOrderViz() {
		return orderViz;
	}

	public void setOrderViz(IVisualTable orderViz) {
		this.orderViz = orderViz;
	}

	public IVisualTable getPosViz() {
		return posViz;
	}

	public void setPosViz(IVisualTable posViz) {
		this.posViz = posViz;
	}

	public IVisualTable getAuditViz() {
		return auditViz;
	}

	public void setAuditViz(IVisualTable auditViz) {
		this.auditViz = auditViz;
	}

	public IVisualTable getAccountViz() {
		return accountViz;
	}

	public void setAccountViz(IVisualTable accountViz) {
		this.accountViz = accountViz;
	}

	public Logger getLog() {
		return log;
	}

	public boolean isAuditLog() {
		return auditLog;
	}

	public void setAuditLog(boolean auditLog) {
		this.auditLog = auditLog;
	}

	public IRiskCalculator getRiskCalculator() {
		return riskCalculator;
	}

	public void setRiskCalculator(IRiskCalculator riskCalculator) {
		this.riskCalculator = riskCalculator;
	}

	public AlgoConfig getAlgoConfig() {
		return algoConfig;
	}

	public void setAlgoConfig(AlgoConfig algoConfig) {
		this.algoConfig = algoConfig;
	}

}
