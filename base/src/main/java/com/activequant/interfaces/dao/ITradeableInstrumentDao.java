package com.activequant.interfaces.dao;

import com.activequant.domainmodel.Instrument;
import com.activequant.domainmodel.MarketDataInstrument;
import com.activequant.domainmodel.TradeableInstrument;

public interface ITradeableInstrumentDao extends IEntityDao<TradeableInstrument> {
  
	TradeableInstrument findByProvId(String providerId, String provSpecInstId);
	TradeableInstrument findFor(String providerId, Instrument instrument);
	TradeableInstrument[] findFor(Instrument instrument);

}
