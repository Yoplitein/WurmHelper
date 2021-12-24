package net.ildar.wurm;

import java.util.Objects;

public class Pair<Key, Value>
{
	private Key key;
	private Value value;
	
	public Pair(Key key, Value value)
	{
		this.key = key;
		this.value = value;
	}
	
	public Key getKey() { return key; }
	public Value getValue() { return value; }
	
	@Override
	public int hashCode() {
		return Objects.hash(key, value);
	}
	
	@Override
	@SuppressWarnings("rawtypes")
	public boolean equals(Object obj) {
		if(this == obj) return true;
		
		Pair pair = (Pair)obj;
		if(pair == null) return false;
		return Objects.equals(key, pair.key) && Objects.equals(value, pair.value);
	}
}
