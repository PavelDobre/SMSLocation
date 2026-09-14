package pt.dobre.smslocation.adapters;

import java.util.ArrayList;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import pt.dobre.smslocation.R;
import pt.dobre.smslocation.data.ListItem;

final public class ListItemAdapter extends ArrayAdapter<ListItem>
                                   implements View.OnClickListener, View.OnLongClickListener {
  public interface ListManager {
    void requestLocation(ListItem item);
    void requestRing(ListItem item);
    void requestRingStop(ListItem item);
    void editItem(int position);
  }

  ArrayList<ListItem> items;
  private final ListManager listManager;

  private static class ViewHolder {
    ImageView ignoreIndicator;
    TextView itemText;
    Button requestButton;
    Button ringButton;
  }

  public ListItemAdapter(ArrayList<ListItem> items, ListManager lr, Context context) {
      super(context, R.layout.row_item, items);
      this.items = items;
      this.listManager = lr;
  }

  @Override
  public void onClick(View v) {
    int position = (Integer) v.getTag();

    if (v.getId() == R.id.requestLocationButton) {
      ListItem item = getItem(position);
      this.listManager.requestLocation(item);
    }
    if (v.getId() == R.id.requestRingButton) {
      ListItem item = getItem(position);
      this.listManager.requestRing(item);
    }
    if (v.getId() == R.id.listItemTextView) {
      this.listManager.editItem(position);
    }
  }

  @Override
  public boolean onLongClick(View v) {
    if (v.getId() != R.id.requestRingButton || !(v.getTag() instanceof Integer)) {
      return false;
    }

    int position = (Integer) v.getTag();
    ListItem item = getItem(position);
    if (item == null) {
      return false;
    }
    this.listManager.requestRingStop(item);
    return true;
  }

  @NonNull
  @Override
  public View getView(int position, View convertView, @NonNull ViewGroup parent) {
    // Get the data item for this position
    ListItem item = getItem(position);
    // Check if an existing view is being reused, otherwise inflate the view
    ViewHolder viewHolder; // view lookup cache stored in tag

    if (convertView == null) {
      viewHolder = new ViewHolder();
      LayoutInflater inflater = LayoutInflater.from(getContext());
      convertView = inflater.inflate(R.layout.row_item, parent, false);
      viewHolder.ignoreIndicator = convertView.findViewById(R.id.ignoreImageView);
      viewHolder.itemText = convertView.findViewById(R.id.listItemTextView);
      viewHolder.requestButton = convertView.findViewById(R.id.requestLocationButton);
      viewHolder.ringButton = convertView.findViewById(R.id.requestRingButton);

      convertView.setTag(viewHolder);
    } else {
      viewHolder = (ViewHolder) convertView.getTag();
    }

    // Bind the data
    assert item != null;
    if (item.getIgnoreRequests()) {
      viewHolder.ignoreIndicator.setVisibility (View.VISIBLE);
    } else {
      viewHolder.ignoreIndicator.setVisibility (View.INVISIBLE);
    }
    viewHolder.itemText.setText(item.toString());
    viewHolder.itemText.setOnClickListener(this);
    viewHolder.itemText.setTag(position);
    viewHolder.requestButton.setOnClickListener(this);
    viewHolder.requestButton.setTag(position);

    boolean ringConfigured = !item.getAlarmMessagePrefix().isEmpty();
    viewHolder.ringButton.setEnabled(ringConfigured);
    viewHolder.ringButton.setOnClickListener(this);
    viewHolder.ringButton.setOnLongClickListener(this);
    viewHolder.ringButton.setTag(position);

    // Return the completed view to render on screen
    return convertView;
  }
}
