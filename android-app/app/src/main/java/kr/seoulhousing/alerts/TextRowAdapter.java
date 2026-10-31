package kr.seoulhousing.alerts;
import android.view.*; import android.widget.TextView; import androidx.recyclerview.widget.RecyclerView; import java.util.*;
final class TextRowAdapter extends RecyclerView.Adapter<TextRowAdapter.Row> {
    interface Listener { void selected(String id); } private final List<String> lines; private final List<String> ids; private final Listener listener;
    TextRowAdapter(List<String> lines,List<String> ids,Listener listener) { this.lines=lines;this.ids=ids;this.listener=listener; }
    public Row onCreateViewHolder(ViewGroup p,int t) { return new Row(LayoutInflater.from(p.getContext()).inflate(R.layout.item_row,p,false)); }
    public void onBindViewHolder(Row h,int p) { h.text.setText(lines.get(p)); h.itemView.setOnClickListener(v->listener.selected(ids.get(p))); } public int getItemCount(){return lines.size();}
    static class Row extends RecyclerView.ViewHolder { TextView text; Row(View v){super(v);text=v.findViewById(R.id.row_text);} }
}
